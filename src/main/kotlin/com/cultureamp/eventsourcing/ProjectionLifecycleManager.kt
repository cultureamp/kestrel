package com.cultureamp.eventsourcing

/**
 * Status of a projection in its lifecycle
 */
sealed class ProjectionLifecycleStatus
data class ProjectionActive(val name: String, val currentSequence: Long) : ProjectionLifecycleStatus()
data class ProjectionBuilding(val name: String, val currentSequence: Long, val targetSequence: Long, val progress: Double) : ProjectionLifecycleStatus()
data class ProjectionReadyForPromotion(val name: String, val currentSequence: Long) : ProjectionLifecycleStatus()
data class ProjectionError(val name: String, val error: String) : ProjectionLifecycleStatus()

/**
 * A/B substitution operation result
 */
sealed class SubstitutionResult
data class SubstitutionSuccess(val message: String, val oldProjection: String, val newProjection: String) : SubstitutionResult()
data class SubstitutionError(val error: String, val rollbackInstructions: List<String> = emptyList()) : SubstitutionResult()

/**
 * Projection configuration for lifecycle management
 */
data class ProjectionLifecycleConfig(
    val name: String,
    val eventProcessor: EventProcessor<*>,
    val bookmarkStore: BookmarkStore,
    val targetSequence: Long? = null // Optional target sequence for catch-up validation
)

/**
 * Manages projection lifecycles for A/B substitution scenarios.
 *
 * This manager provides operational tooling for:
 * - Building async projections that will be promoted to sync
 * - Monitoring projection catch-up progress
 * - Safely executing A/B substitutions
 * - Managing projection promotion and rollback
 */
class ProjectionLifecycleManager<M : EventMetadata>(
    private val catchupValidator: ProjectionCatchupValidator,
    private val configurationManager: SyncProcessorConfigurationManager<M>,
    private val eventStore: EventSource<M>? = null
) {

    /**
     * Starts building a new projection asynchronously with catch-up monitoring.
     *
     * @param config Configuration for the new projection
     * @param targetProjectionName Optional name of projection to catch up to
     * @return Status of the building projection
     */
    fun startProjectionBuild(
        config: ProjectionLifecycleConfig,
        targetProjectionName: String? = null
    ): ProjectionLifecycleStatus {
        return try {
            // Get target sequence if specified
            val targetSequence = when {
                config.targetSequence != null -> config.targetSequence
                targetProjectionName != null -> {
                    val targetBookmark = config.bookmarkStore.bookmarkFor(targetProjectionName)
                    targetBookmark.sequence
                }
                eventStore != null -> {
                    // Get latest sequence from event store if available
                    // This would need to be implemented in the EventSource interface
                    0L // Placeholder - in real implementation would query current max sequence
                }
                else -> 0L
            }

            val currentBookmark = config.bookmarkStore.bookmarkFor(config.name)
            val progress = if (targetSequence > 0) {
                (currentBookmark.sequence.toDouble() / targetSequence.toDouble()).coerceAtMost(1.0)
            } else {
                0.0
            }

            if (currentBookmark.sequence >= targetSequence) {
                ProjectionReadyForPromotion(config.name, currentBookmark.sequence)
            } else {
                ProjectionBuilding(config.name, currentBookmark.sequence, targetSequence, progress)
            }
        } catch (e: Exception) {
            ProjectionError(config.name, "Failed to start projection build: ${e.message}")
        }
    }

    /**
     * Monitors the status of multiple projections.
     *
     * @param projectionConfigs List of projection configurations to monitor
     * @return Map of projection name to status
     */
    fun monitorProjections(projectionConfigs: List<ProjectionLifecycleConfig>): Map<String, ProjectionLifecycleStatus> {
        return projectionConfigs.associate { config ->
            config.name to getProjectionStatus(config)
        }
    }

    /**
     * Gets the current status of a projection.
     *
     * @param config Projection configuration
     * @return Current projection status
     */
    fun getProjectionStatus(config: ProjectionLifecycleConfig): ProjectionLifecycleStatus {
        return try {
            val currentBookmark = config.bookmarkStore.bookmarkFor(config.name)

            when {
                config.targetSequence == null -> ProjectionActive(config.name, currentBookmark.sequence)
                currentBookmark.sequence >= config.targetSequence -> ProjectionReadyForPromotion(config.name, currentBookmark.sequence)
                else -> {
                    val progress = currentBookmark.sequence.toDouble() / config.targetSequence.toDouble()
                    ProjectionBuilding(config.name, currentBookmark.sequence, config.targetSequence, progress)
                }
            }
        } catch (e: Exception) {
            ProjectionError(config.name, "Failed to get projection status: ${e.message}")
        }
    }

    /**
     * Executes an A/B substitution by promoting a candidate projection to replace the current one.
     *
     * @param currentProjectionName Name of the currently active projection
     * @param candidateProjectionName Name of the projection to promote
     * @param candidateConfig Configuration for the candidate projection
     * @return Result of the substitution operation
     */
    fun executeABSubstitution(
        currentProjectionName: String,
        candidateProjectionName: String,
        candidateConfig: SyncProcessorConfig
    ): SubstitutionResult {
        return try {
            // Step 1: Validate candidate is ready
            val substitutionPlan = configurationManager.planABSubstitution(currentProjectionName, candidateProjectionName)

            when (substitutionPlan) {
                is ConfigurationChangeError -> {
                    return SubstitutionError(
                        "A/B substitution validation failed: ${substitutionPlan.error}",
                        listOf(
                            "Wait for '$candidateProjectionName' to catch up to '$currentProjectionName'",
                            "Monitor projection progress with monitorProjections()",
                            "Retry substitution when validation passes"
                        )
                    )
                }
                is ConfigurationChangeSuccess -> {
                    // Step 2: Create new sync processor configuration
                    val newConfigResult = configurationManager.createSyncProcessor(listOf(candidateConfig))

                    when (newConfigResult) {
                        is Left -> {
                            SubstitutionError(
                                "Failed to create new sync processor configuration: ${newConfigResult.error.error}",
                                listOf("Check candidate processor configuration", "Ensure processor is active")
                            )
                        }
                        is Right -> {
                            // Step 3: Success - provide deployment instructions
                            SubstitutionSuccess(
                                "A/B substitution ready for deployment. " +
                                "Replace EventStore endOfSinkTransactionHook with new BlockingSyncEventProcessor. " +
                                "Old projection: '$currentProjectionName', New projection: '$candidateProjectionName'",
                                currentProjectionName,
                                candidateProjectionName
                            )
                        }
                    }
                }
                else -> SubstitutionError("Unknown substitution plan result")
            }
        } catch (e: Exception) {
            SubstitutionError(
                "Failed to execute A/B substitution: ${e.message}",
                listOf("Check projection configurations", "Ensure bookmark stores are accessible")
            )
        }
    }

    /**
     * Plans the rollback of an A/B substitution.
     *
     * @param originalProjectionName Name of the original projection to rollback to
     * @param originalConfig Original projection configuration
     * @return Result with rollback instructions
     */
    fun planRollback(
        originalProjectionName: String,
        originalConfig: SyncProcessorConfig
    ): SubstitutionResult {
        return try {
            val rollbackPlan = configurationManager.createRollbackPlan(listOf(originalConfig))

            when (rollbackPlan) {
                is ConfigurationChangeSuccess -> {
                    SubstitutionSuccess(
                        "Rollback plan ready: Revert EventStore endOfSinkTransactionHook to use '$originalProjectionName'",
                        "current_projection",
                        originalProjectionName
                    )
                }
                is ConfigurationChangeError -> {
                    SubstitutionError("Failed to create rollback plan: ${rollbackPlan.error}")
                }
                else -> SubstitutionError("Unknown rollback plan result")
            }
        } catch (e: Exception) {
            SubstitutionError("Failed to plan rollback: ${e.message}")
        }
    }

    /**
     * Provides a summary report of all projection statuses.
     *
     * @param projectionConfigs List of projections to report on
     * @return Human-readable status report
     */
    fun generateStatusReport(projectionConfigs: List<ProjectionLifecycleConfig>): String {
        val statuses = monitorProjections(projectionConfigs)
        val report = StringBuilder()

        report.appendLine("=== Projection Lifecycle Status Report ===")
        report.appendLine()

        statuses.forEach { (name, status) ->
            when (status) {
                is ProjectionActive -> {
                    report.appendLine("✅ $name: ACTIVE (sequence: ${status.currentSequence})")
                }
                is ProjectionBuilding -> {
                    val progressPercent = (status.progress * 100).toInt()
                    report.appendLine("🏗️ $name: BUILDING ${progressPercent}% (${status.currentSequence}/${status.targetSequence})")
                }
                is ProjectionReadyForPromotion -> {
                    report.appendLine("🚀 $name: READY FOR PROMOTION (sequence: ${status.currentSequence})")
                }
                is ProjectionError -> {
                    report.appendLine("❌ $name: ERROR - ${status.error}")
                }
            }
        }

        report.appendLine()

        val readyForPromotion = statuses.values.filterIsInstance<ProjectionReadyForPromotion>()
        if (readyForPromotion.isNotEmpty()) {
            report.appendLine("Projections ready for A/B substitution:")
            readyForPromotion.forEach { status ->
                report.appendLine("- ${status.name}")
            }
        }

        return report.toString()
    }

    /**
     * Validates that all projections in a configuration are in a healthy state.
     *
     * @param projectionConfigs List of projections to validate
     * @return List of validation issues, empty if all projections are healthy
     */
    fun validateProjectionHealth(projectionConfigs: List<ProjectionLifecycleConfig>): List<String> {
        val issues = mutableListOf<String>()
        val statuses = monitorProjections(projectionConfigs)

        statuses.forEach { (name, status) ->
            when (status) {
                is ProjectionError -> {
                    issues.add("Projection '$name' has error: ${status.error}")
                }
                is ProjectionBuilding -> {
                    if (status.progress < 0.1) {
                        issues.add("Projection '$name' build progress is very low (${(status.progress * 100).toInt()}%)")
                    }
                }
                else -> {
                    // ProjectionActive and ProjectionReadyForPromotion are considered healthy
                }
            }
        }

        return issues
    }
}