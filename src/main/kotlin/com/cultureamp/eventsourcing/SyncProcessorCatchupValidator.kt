package com.cultureamp.eventsourcing

/**
 * Error types specific to synchronous processor catchup validation
 */
sealed class SyncCatchupValidationError
data class SyncCatchupValidationFailed(
    val processorName: String,
    val validationResults: List<CatchupValidationResult>
) : SyncCatchupValidationError()
data class SyncCatchupValidationException(val cause: Throwable) : SyncCatchupValidationError()


/**
 * Validates processor catchup status before synchronous event processing.
 *
 * This validator ensures that async-built projectors are sufficiently caught up
 * before being used in synchronous processing scenarios, preventing data gaps
 * when switching from async to sync processing modes.
 */
class SyncProcessorCatchupValidator<M : EventMetadata>(
    private val eventsSequenceStats: EventsSequenceStats,
    private val defaultConfig: CatchupValidationConfig = CatchupValidationConfig()
) {

    /**
     * Validates all processors are caught up before synchronous processing begins.
     *
     * @param processors List of processors to validate
     * @param validationConfigs Map of processor name to specific validation config
     * @return Either validation error or Unit on success
     */
    fun validateProcessorsBeforeSync(
        processors: List<BookmarkedEventProcessor<M>>,
        validationConfigs: Map<String, CatchupValidationConfig> = emptyMap()
    ): Either<SyncCatchupValidationError, Unit> {

        if (processors.isEmpty()) {
            return Right(Unit)
        }

        return try {
            // Get the latest sequence number from the event store
            val latestSequence = eventsSequenceStats.lastSequence()

            // Validate each processor
            val allResults = mutableListOf<CatchupValidationResult>()
            val failedProcessors = mutableListOf<String>()

            for (processor in processors) {
                val processorName = processor.bookmarkName
                val config = validationConfigs[processorName] ?: defaultConfig

                // Skip validation if configured
                if (config.validationMode == CatchupValidationMode.SKIP) {
                    continue
                }

                val validator = ProjectionCatchupValidator(
                    processor.bookmarkStore,
                    CatchupValidationConfig(
                        allowableGap = config.allowableGap,
                        allowAhead = config.allowAhead
                    )
                )

                val result = validator.validateCatchup(processorName, latestSequence)
                allResults.add(result)

                when (result) {
                    is CatchupValid -> {
                        // Success - processor is caught up
                    }
                    is CatchupBehind -> {
                        when (config.validationMode) {
                            CatchupValidationMode.ENFORCE -> {
                                failedProcessors.add(processorName)
                            }
                            CatchupValidationMode.WARN -> {
                                logWarning("Processor '$processorName' is ${result.gap} events behind (sequence ${result.currentSequence} vs ${result.targetSequence})")
                            }
                            CatchupValidationMode.SKIP -> {
                                // Already handled above
                            }
                        }
                    }
                    is CatchupAhead -> {
                        when (config.validationMode) {
                            CatchupValidationMode.ENFORCE -> {
                                if (!config.allowAhead) {
                                    failedProcessors.add(processorName)
                                }
                            }
                            CatchupValidationMode.WARN -> {
                                logWarning("Processor '$processorName' is ${result.gap} events ahead (sequence ${result.currentSequence} vs ${result.targetSequence})")
                            }
                            CatchupValidationMode.SKIP -> {
                                // Already handled above
                            }
                        }
                    }
                    is CatchupError -> {
                        when (config.validationMode) {
                            CatchupValidationMode.ENFORCE -> {
                                failedProcessors.add(processorName)
                            }
                            CatchupValidationMode.WARN -> {
                                logWarning("Processor '$processorName' validation error: ${result.error}")
                            }
                            CatchupValidationMode.SKIP -> {
                                // Already handled above
                            }
                        }
                    }
                }
            }

            // Check if any processors failed validation in ENFORCE mode
            if (failedProcessors.isNotEmpty()) {
                val failedResults = allResults.filter { result ->
                    when (result) {
                        is CatchupValid -> false
                        is CatchupBehind -> failedProcessors.contains(result.projectionName)
                        is CatchupAhead -> failedProcessors.contains(result.projectionName)
                        is CatchupError -> failedProcessors.contains(result.projectionName)
                    }
                }
                Left(SyncCatchupValidationFailed("Multiple processors", failedResults))
            } else {
                Right(Unit)
            }

        } catch (e: Exception) {
            Left(SyncCatchupValidationException(e))
        }
    }

    /**
     * Validates a single processor's catchup status.
     *
     * @param processor The processor to validate
     * @param config Validation configuration for this processor
     * @return Either validation error or Unit on success
     */
    fun validateProcessorBeforeSync(
        processor: BookmarkedEventProcessor<M>,
        config: CatchupValidationConfig = defaultConfig
    ): Either<SyncCatchupValidationError, Unit> {
        return validateProcessorsBeforeSync(listOf(processor), mapOf(processor.bookmarkName to config))
    }

    /**
     * Gets validation status for processors without enforcing.
     * Useful for reporting and monitoring.
     *
     * @param processors List of processors to check
     * @return Map of processor name to validation result
     */
    fun getValidationStatus(processors: List<BookmarkedEventProcessor<M>>): Map<String, CatchupValidationResult> {
        return try {
            val latestSequence = eventsSequenceStats.lastSequence()

            processors.associate { processor ->
                val validator = ProjectionCatchupValidator(processor.bookmarkStore)
                processor.bookmarkName to validator.validateCatchup(processor.bookmarkName, latestSequence)
            }
        } catch (e: Exception) {
            processors.associate { processor ->
                processor.bookmarkName to CatchupError(processor.bookmarkName, e.message ?: "Unknown error")
            }
        }
    }

    private fun logWarning(message: String) {
        System.err.println("[WARNING] SyncProcessorCatchupValidator: $message")
    }
}