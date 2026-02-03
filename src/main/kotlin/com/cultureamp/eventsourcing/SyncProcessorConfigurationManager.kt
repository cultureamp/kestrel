package com.cultureamp.eventsourcing

/**
 * Configuration for synchronous processor deployment
 */
data class SyncProcessorConfig(
    val processorName: String,
    val eventProcessor: EventProcessor<*>,
    val bookmarkStore: BookmarkStore,
    val isActive: Boolean = true,
    // New field for catchup validation
    val catchupValidationConfig: CatchupValidationConfig = CatchupValidationConfig()
)

/**
 * Result of a configuration change operation
 */
sealed class ConfigurationChangeResult
data class ConfigurationChangeSuccess(val message: String) : ConfigurationChangeResult()
data class ConfigurationChangeError(val error: String) : ConfigurationChangeResult()
data class ConfigurationValidationError(val validationErrors: List<String>) : ConfigurationChangeResult()

/**
 * Manages synchronous processor configurations for A/B substitution scenarios.
 *
 * This manager helps with deployment scenarios where async-built projections need to be
 * promoted to synchronous processing, enabling safe A/B swaps without downtime.
 */
class SyncProcessorConfigurationManager<M : EventMetadata>(
    private val catchupValidator: ProjectionCatchupValidator,
    private val syncCatchupValidator: SyncProcessorCatchupValidator<M>? = null,
    private val timeoutMs: Long = 5000
) {

    /**
     * Validates that a new processor configuration is safe to deploy.
     *
     * @param newConfigs List of processor configurations to validate
     * @param requiredCatchupValidations Optional map of processor name to target sequence for catch-up validation
     * @return ConfigurationChangeResult indicating validation status
     */
    fun validateConfiguration(
        newConfigs: List<SyncProcessorConfig>,
        requiredCatchupValidations: Map<String, Long> = emptyMap()
    ): ConfigurationChangeResult {
        val errors = mutableListOf<String>()

        // Check for duplicate processor names
        val processorNames = newConfigs.map { it.processorName }
        val duplicates = processorNames.groupBy { it }.filterValues { it.size > 1 }.keys
        if (duplicates.isNotEmpty()) {
            errors.add("Duplicate processor names found: ${duplicates.joinToString(", ")}")
        }

        // Check for inactive processors without explanation
        val inactiveProcessors = newConfigs.filter { !it.isActive }
        if (inactiveProcessors.isNotEmpty()) {
            errors.add("Inactive processors found (ensure this is intentional): ${inactiveProcessors.map { it.processorName }}")
        }

        // Validate catch-up status if required
        if (requiredCatchupValidations.isNotEmpty()) {
            val catchupResults = catchupValidator.validateMultipleCatchups(requiredCatchupValidations)
            val failedCatchups = catchupResults.filterValues { it !is CatchupValid }
            if (failedCatchups.isNotEmpty()) {
                errors.addAll(failedCatchups.map { (name, result) ->
                    when (result) {
                        is CatchupBehind -> "Processor '$name' is behind: ${result.currentSequence}/${result.targetSequence} (gap: ${result.gap})"
                        is CatchupAhead -> "Processor '$name' is ahead: ${result.currentSequence}/${result.targetSequence} (gap: ${result.gap})"
                        is CatchupError -> "Processor '$name' validation error: ${result.error}"
                        else -> "Processor '$name' validation failed"
                    }
                })
            }
        }

        // Additional pre-deployment catchup validation if sync validator is available
        if (syncCatchupValidator != null) {
            val bookmarkedProcessors = newConfigs.filter { it.isActive }.map { config ->
                @Suppress("UNCHECKED_CAST")
                BookmarkedEventProcessor.from(
                    bookmarkStore = config.bookmarkStore,
                    bookmarkName = config.processorName,
                    eventProcessor = config.eventProcessor as EventProcessor<M>
                )
            }

            val validationConfigsMap = newConfigs.associate { it.processorName to it.catchupValidationConfig }
            val syncValidationResult = syncCatchupValidator.validateProcessorsBeforeSync(bookmarkedProcessors, validationConfigsMap)

            when (syncValidationResult) {
                is Left -> {
                    when (val error = syncValidationResult.error) {
                        is SyncCatchupValidationFailed -> {
                            errors.addAll(error.validationResults.map { result ->
                                when (result) {
                                    is CatchupBehind -> "Sync validation failed for '${result.projectionName}': behind by ${result.gap} events (${result.currentSequence}/${result.targetSequence})"
                                    is CatchupAhead -> "Sync validation warning for '${result.projectionName}': ahead by ${result.gap} events (${result.currentSequence}/${result.targetSequence})"
                                    is CatchupError -> "Sync validation error for '${result.projectionName}': ${result.error}"
                                    is CatchupValid -> "Sync validation passed for '${result.projectionName}' (sequence: ${result.sequence})"
                                }
                            })
                        }
                        is SyncCatchupValidationException -> {
                            errors.add("Sync validation exception: ${error.cause.message}")
                        }
                    }
                }
                is Right -> {
                    // Validation passed
                }
            }
        }

        return if (errors.isEmpty()) {
            ConfigurationChangeSuccess("Configuration validation passed for ${newConfigs.size} processors")
        } else {
            ConfigurationValidationError(errors)
        }
    }

    /**
     * Creates a BlockingSyncEventProcessor from the given configuration.
     *
     * @param configs List of processor configurations
     * @param logger Optional logger function
     * @param enableValidation Whether to enable catchup validation (default true)
     * @return BlockingSyncEventProcessor configured with the given processors
     */
    fun createSyncProcessor(
        configs: List<SyncProcessorConfig>,
        logger: (String) -> Unit = System.out::println,
        enableValidation: Boolean = true
    ): Either<ConfigurationChangeError, BlockingSyncEventProcessor<M>> {
        return try {
            val activeConfigs = configs.filter { it.isActive }

            if (activeConfigs.isEmpty()) {
                return Left(ConfigurationChangeError("No active processors found in configuration"))
            }

            val bookmarkedProcessors = activeConfigs.map { config ->
                @Suppress("UNCHECKED_CAST")
                BookmarkedEventProcessor.from(
                    bookmarkStore = config.bookmarkStore,
                    bookmarkName = config.processorName,
                    eventProcessor = config.eventProcessor as EventProcessor<M>
                )
            }

            // Build validation configuration map from processor configs
            val validationConfigs = activeConfigs.associate { config ->
                config.processorName to config.catchupValidationConfig
            }

            val syncProcessor: BlockingSyncEventProcessor<M> = BlockingSyncEventProcessor<M>(
                eventProcessors = bookmarkedProcessors,
                timeoutMs = timeoutMs,
                logger = logger,
                catchupValidator = if (enableValidation) syncCatchupValidator else null,
                validationConfigs = validationConfigs
            )

            Right(syncProcessor)
        } catch (e: Exception) {
            Left(ConfigurationChangeError("Failed to create sync processor: ${e.message}"))
        }
    }

    /**
     * Helps plan an A/B substitution by validating the candidate projection catch-up status.
     *
     * @param currentProcessorName Name of the currently active processor (e.g., "projection_a")
     * @param candidateProcessorName Name of the candidate processor (e.g., "projection_b_builder")
     * @return ConfigurationChangeResult with validation status and recommendations
     */
    fun planABSubstitution(
        currentProcessorName: String,
        candidateProcessorName: String
    ): ConfigurationChangeResult {
        return try {
            val catchupResult = catchupValidator.validateCatchupToProjection(candidateProcessorName, currentProcessorName)

            when (catchupResult) {
                is CatchupValid -> ConfigurationChangeSuccess(
                    "A/B substitution ready: '$candidateProcessorName' (seq: ${catchupResult.sequence}) " +
                    "can replace '$currentProcessorName'. Deploy with new sync processor configuration."
                )
                is CatchupBehind -> ConfigurationChangeError(
                    "A/B substitution not ready: '$candidateProcessorName' is behind by ${catchupResult.gap} events " +
                    "(${catchupResult.currentSequence}/${catchupResult.targetSequence}). Wait for catch-up."
                )
                is CatchupAhead -> ConfigurationChangeSuccess(
                    "A/B substitution ready: '$candidateProcessorName' (seq: ${catchupResult.currentSequence}) " +
                    "is ahead of '$currentProcessorName' (seq: ${catchupResult.targetSequence}). Safe to deploy."
                )
                is CatchupError -> ConfigurationChangeError(
                    "A/B substitution validation failed: ${catchupResult.error}"
                )
            }
        } catch (e: Exception) {
            ConfigurationChangeError("Failed to plan A/B substitution: ${e.message}")
        }
    }

    /**
     * Generates a deployment checklist for A/B substitution.
     *
     * @param currentProcessorName Name of current processor
     * @param candidateProcessorName Name of candidate processor
     * @param additionalProcessors Optional list of other processors in the configuration
     * @return List of deployment steps
     */
    fun generateDeploymentChecklist(
        currentProcessorName: String,
        candidateProcessorName: String,
        additionalProcessors: List<String> = emptyList()
    ): List<String> {
        return listOf(
            "1. Validate catch-up status: Ensure '$candidateProcessorName' has caught up to '$currentProcessorName'",
            "2. Test candidate processor: Verify '$candidateProcessorName' processes events correctly",
            "3. Prepare new configuration: Create SyncProcessorConfig with '$candidateProcessorName' as active",
            "4. Update EventStore hook: Replace endOfSinkTransactionHook with new BlockingSyncEventProcessor",
            if (additionalProcessors.isNotEmpty())
                "5. Coordinate additional processors: Ensure ${additionalProcessors.joinToString(", ")} are also ready"
            else
                "5. Monitor deployment: Watch for any processor failures during deployment",
            "6. Validate deployment: Confirm new processor is receiving and processing events",
            "7. Monitor performance: Ensure sync processor doesn't impact command processing latency",
            "8. Cleanup: Remove old async '$currentProcessorName' processor if no longer needed"
        ).filterNot { it.isBlank() }
    }

    /**
     * Creates a safe rollback configuration that can be used if deployment fails.
     *
     * @param originalConfig Original processor configuration before deployment
     * @return ConfigurationChangeResult with rollback instructions
     */
    fun createRollbackPlan(originalConfig: List<SyncProcessorConfig>): ConfigurationChangeResult {
        return try {
            if (originalConfig.isEmpty()) {
                ConfigurationChangeError("No original configuration provided for rollback")
            } else {
                val activeProcessors = originalConfig.filter { it.isActive }
                ConfigurationChangeSuccess(
                    "Rollback plan ready: Revert to ${activeProcessors.size} original processors: " +
                    activeProcessors.joinToString(", ") { it.processorName }
                )
            }
        } catch (e: Exception) {
            ConfigurationChangeError("Failed to create rollback plan: ${e.message}")
        }
    }
}