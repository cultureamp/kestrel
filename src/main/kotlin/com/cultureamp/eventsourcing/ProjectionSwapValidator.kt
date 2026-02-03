package com.cultureamp.eventsourcing

/**
 * Validation result for projection swap safety
 */
sealed class SwapValidationResult
data class SwapSafe(val message: String, val checklist: List<String>) : SwapValidationResult()
data class SwapUnsafe(val issues: List<String>, val recommendations: List<String>) : SwapValidationResult()
data class SwapValidationError(val error: String) : SwapValidationResult()

/**
 * Safety check configuration for projection swaps
 */
data class ProjectionSwapSafetyConfig(
    val maxAllowableGap: Long = 0, // Maximum events candidate can be behind
    val requireHealthChecks: Boolean = true, // Require projection health validation
    val requireProgressChecks: Boolean = true, // Require minimum progress validation
    val minProgressThreshold: Double = 0.95, // Minimum progress (95%) for swap readiness
    val maxErrorRate: Double = 0.01, // Maximum acceptable error rate (1%)
    val requireRollbackPlan: Boolean = true // Require rollback plan validation
)

/**
 * Comprehensive context for a projection swap operation
 */
data class ProjectionSwapContext<M : EventMetadata>(
    val currentProjectionName: String,
    val candidateProjectionName: String,
    val currentConfig: SyncProcessorConfig,
    val candidateConfig: SyncProcessorConfig,
    val safetyConfig: ProjectionSwapSafetyConfig = ProjectionSwapSafetyConfig()
)

/**
 * Automated validator for safe projection swaps in A/B substitution scenarios.
 *
 * This validator combines all the A/B substitution tooling to provide comprehensive
 * safety validation before executing projection swaps. It performs multiple layers
 * of validation to ensure swap safety.
 */
class ProjectionSwapValidator<M : EventMetadata>(
    private val catchupValidator: ProjectionCatchupValidator,
    private val configurationManager: SyncProcessorConfigurationManager<M>,
    private val lifecycleManager: ProjectionLifecycleManager<M>
) {

    /**
     * Performs comprehensive validation of a projection swap operation.
     *
     * @param swapContext Context containing all swap operation details
     * @return SwapValidationResult with detailed validation results
     */
    fun validateSwap(swapContext: ProjectionSwapContext<M>): SwapValidationResult {
        return try {
            val issues = mutableListOf<String>()
            val recommendations = mutableListOf<String>()
            val checklist = mutableListOf<String>()

            // Level 1: Basic catch-up validation
            val catchupResult = catchupValidator.validateCatchupToProjection(
                swapContext.candidateProjectionName,
                swapContext.currentProjectionName
            )

            when (catchupResult) {
                is CatchupBehind -> {
                    if (catchupResult.gap > swapContext.safetyConfig.maxAllowableGap) {
                        issues.add("Candidate projection '${swapContext.candidateProjectionName}' is ${catchupResult.gap} events behind current projection '${swapContext.currentProjectionName}'")
                        recommendations.add("Wait for candidate projection to catch up within ${swapContext.safetyConfig.maxAllowableGap} events")
                    }
                }
                is CatchupError -> {
                    issues.add("Failed to validate catch-up status: ${catchupResult.error}")
                    recommendations.add("Check bookmark store connectivity and projection configurations")
                }
                else -> {
                    checklist.add("✅ Catch-up validation: Candidate projection is sufficiently caught up")
                }
            }

            // Level 2: Configuration validation
            val configValidation = configurationManager.validateConfiguration(
                listOf(swapContext.candidateConfig),
                mapOf(swapContext.candidateProjectionName to
                    catchupValidator.validateCatchupToProjection(swapContext.candidateProjectionName, swapContext.currentProjectionName).let {
                        when (it) {
                            is CatchupValid -> it.sequence
                            is CatchupBehind -> it.targetSequence
                            is CatchupAhead -> it.targetSequence
                            else -> 0L
                        }
                    }
                )
            )

            when (configValidation) {
                is ConfigurationValidationError -> {
                    issues.addAll(configValidation.validationErrors)
                    recommendations.add("Fix configuration issues before proceeding with swap")
                }
                is ConfigurationChangeSuccess -> {
                    checklist.add("✅ Configuration validation: Candidate processor configuration is valid")
                }
                else -> {
                    issues.add("Unexpected configuration validation result")
                }
            }

            // Level 3: Health checks (if enabled)
            if (swapContext.safetyConfig.requireHealthChecks) {
                val candidateLifecycleConfig = ProjectionLifecycleConfig(
                    name = swapContext.candidateProjectionName,
                    eventProcessor = swapContext.candidateConfig.eventProcessor,
                    bookmarkStore = swapContext.candidateConfig.bookmarkStore
                )

                val healthIssues = lifecycleManager.validateProjectionHealth(listOf(candidateLifecycleConfig))
                if (healthIssues.isNotEmpty()) {
                    issues.addAll(healthIssues)
                    recommendations.add("Resolve health issues before proceeding with swap")
                } else {
                    checklist.add("✅ Health validation: Candidate projection is healthy")
                }
            }

            // Level 4: Progress checks (if enabled)
            if (swapContext.safetyConfig.requireProgressChecks) {
                val candidateStatus = lifecycleManager.getProjectionStatus(
                    ProjectionLifecycleConfig(
                        name = swapContext.candidateProjectionName,
                        eventProcessor = swapContext.candidateConfig.eventProcessor,
                        bookmarkStore = swapContext.candidateConfig.bookmarkStore,
                        targetSequence = catchupValidator.validateCatchupToProjection(
                            swapContext.candidateProjectionName,
                            swapContext.currentProjectionName
                        ).let {
                            when (it) {
                                is CatchupValid -> it.sequence
                                is CatchupBehind -> it.targetSequence
                                is CatchupAhead -> it.currentSequence
                                else -> null
                            }
                        }
                    )
                )

                when (candidateStatus) {
                    is ProjectionBuilding -> {
                        if (candidateStatus.progress < swapContext.safetyConfig.minProgressThreshold) {
                            issues.add("Candidate projection build progress (${(candidateStatus.progress * 100).toInt()}%) is below minimum threshold (${(swapContext.safetyConfig.minProgressThreshold * 100).toInt()}%)")
                            recommendations.add("Wait for candidate projection to reach minimum progress threshold")
                        } else {
                            checklist.add("✅ Progress validation: Candidate projection build is sufficiently advanced")
                        }
                    }
                    is ProjectionReadyForPromotion -> {
                        checklist.add("✅ Progress validation: Candidate projection is ready for promotion")
                    }
                    is ProjectionError -> {
                        issues.add("Candidate projection has errors: ${candidateStatus.error}")
                        recommendations.add("Resolve projection errors before proceeding")
                    }
                    else -> {
                        checklist.add("✅ Progress validation: Candidate projection status is acceptable")
                    }
                }
            }

            // Level 5: A/B substitution planning
            val substitutionPlan = lifecycleManager.executeABSubstitution(
                swapContext.currentProjectionName,
                swapContext.candidateProjectionName,
                swapContext.candidateConfig
            )

            when (substitutionPlan) {
                is SubstitutionError -> {
                    issues.add("A/B substitution planning failed: ${substitutionPlan.error}")
                    recommendations.addAll(substitutionPlan.rollbackInstructions)
                }
                is SubstitutionSuccess -> {
                    checklist.add("✅ Substitution planning: A/B substitution is ready for execution")
                }
            }

            // Level 6: Rollback plan validation (if enabled)
            if (swapContext.safetyConfig.requireRollbackPlan) {
                val rollbackPlan = lifecycleManager.planRollback(
                    swapContext.currentProjectionName,
                    swapContext.currentConfig
                )

                when (rollbackPlan) {
                    is SubstitutionError -> {
                        issues.add("Rollback plan validation failed: ${rollbackPlan.error}")
                        recommendations.add("Ensure rollback configuration is available and valid")
                    }
                    is SubstitutionSuccess -> {
                        checklist.add("✅ Rollback planning: Rollback plan is available and valid")
                    }
                }
            }

            // Generate final result
            if (issues.isNotEmpty()) {
                SwapUnsafe(issues, recommendations)
            } else {
                checklist.add("")
                checklist.add("🚀 All safety validations passed - swap is SAFE to proceed!")
                SwapSafe(
                    "Projection swap from '${swapContext.currentProjectionName}' to '${swapContext.candidateProjectionName}' has passed all safety validations",
                    checklist
                )
            }

        } catch (e: Exception) {
            SwapValidationError("Failed to validate projection swap: ${e.message}")
        }
    }

    /**
     * Performs a quick safety check focusing only on the most critical validations.
     *
     * @param swapContext Context containing swap operation details
     * @return SwapValidationResult with quick validation results
     */
    fun quickSafetyCheck(swapContext: ProjectionSwapContext<M>): SwapValidationResult {
        return try {
            // Only check catch-up status and basic configuration
            val quickContext = swapContext.copy(
                safetyConfig = swapContext.safetyConfig.copy(
                    requireHealthChecks = false,
                    requireProgressChecks = false,
                    requireRollbackPlan = false
                )
            )
            validateSwap(quickContext)
        } catch (e: Exception) {
            SwapValidationError("Failed to perform quick safety check: ${e.message}")
        }
    }

    /**
     * Validates multiple projection swap scenarios in batch.
     *
     * @param swapContexts List of swap contexts to validate
     * @return Map of swap context identifier to validation result
     */
    fun validateMultipleSwaps(swapContexts: List<ProjectionSwapContext<M>>): Map<String, SwapValidationResult> {
        return swapContexts.associate { context ->
            val identifier = "${context.currentProjectionName} → ${context.candidateProjectionName}"
            identifier to validateSwap(context)
        }
    }

    /**
     * Generates a safety assessment report for a projection swap.
     *
     * @param swapContext Swap context to assess
     * @return Human-readable safety assessment report
     */
    fun generateSafetyReport(swapContext: ProjectionSwapContext<M>): String {
        val validationResult = validateSwap(swapContext)
        val report = StringBuilder()

        report.appendLine("=== Projection Swap Safety Assessment ===")
        report.appendLine()
        report.appendLine("Swap: ${swapContext.currentProjectionName} → ${swapContext.candidateProjectionName}")
        report.appendLine()

        when (validationResult) {
            is SwapSafe -> {
                report.appendLine("🟢 RESULT: SAFE TO PROCEED")
                report.appendLine()
                report.appendLine("Safety Validations:")
                validationResult.checklist.forEach { check ->
                    if (check.isNotBlank()) {
                        report.appendLine(check)
                    }
                }
                report.appendLine()
                report.appendLine(validationResult.message)
            }
            is SwapUnsafe -> {
                report.appendLine("🔴 RESULT: UNSAFE - DO NOT PROCEED")
                report.appendLine()
                report.appendLine("Issues Found:")
                validationResult.issues.forEach { issue ->
                    report.appendLine("❌ $issue")
                }
                report.appendLine()
                report.appendLine("Recommendations:")
                validationResult.recommendations.forEach { recommendation ->
                    report.appendLine("💡 $recommendation")
                }
            }
            is SwapValidationError -> {
                report.appendLine("🟡 RESULT: VALIDATION ERROR")
                report.appendLine()
                report.appendLine("Error: ${validationResult.error}")
                report.appendLine()
                report.appendLine("Cannot determine safety - manual review required.")
            }
        }

        return report.toString()
    }
}