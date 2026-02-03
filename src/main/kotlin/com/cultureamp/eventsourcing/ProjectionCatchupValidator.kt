package com.cultureamp.eventsourcing

/**
 * Validation results for projection catch-up status
 */
sealed class CatchupValidationResult
data class CatchupValid(val projectionName: String, val sequence: Long) : CatchupValidationResult()
data class CatchupBehind(val projectionName: String, val currentSequence: Long, val targetSequence: Long, val gap: Long) : CatchupValidationResult()
data class CatchupAhead(val projectionName: String, val currentSequence: Long, val targetSequence: Long, val gap: Long) : CatchupValidationResult()
data class CatchupError(val projectionName: String, val error: String) : CatchupValidationResult()

/**
 * Configuration for catch-up validation
 */
data class CatchupValidationConfig(
    val allowableGap: Long = 0, // Allow projection to be behind by this many events
    val allowAhead: Boolean = true, // Allow projection to be ahead of target
    val validationMode: CatchupValidationMode = CatchupValidationMode.ENFORCE, // Validation enforcement mode
    val skipValidationOnFirstDeploy: Boolean = false // Skip validation for first deployment
)

enum class CatchupValidationMode {
    ENFORCE,    // Fail processing if validation fails
    WARN,       // Log warning but continue processing
    SKIP        // No validation (emergency override)
}

/**
 * Validates projection catch-up status for A/B substitution scenarios.
 *
 * This validator is used to ensure that async-built projections are sufficiently
 * caught up before being promoted to synchronous processing, enabling safe A/B swaps.
 */
class ProjectionCatchupValidator(
    private val bookmarkStore: BookmarkStore,
    private val config: CatchupValidationConfig = CatchupValidationConfig()
) {

    /**
     * Validates that a projection has caught up to a target sequence number.
     *
     * @param projectionName Name of the projection to validate
     * @param targetSequence Target sequence the projection should have reached
     * @return CatchupValidationResult indicating validation status
     */
    fun validateCatchup(projectionName: String, targetSequence: Long): CatchupValidationResult {
        return try {
            val bookmark = bookmarkStore.bookmarkFor(projectionName)
            val currentSequence = bookmark.sequence
            val gap = targetSequence - currentSequence

            when {
                gap < 0 && !config.allowAhead ->
                    CatchupAhead(projectionName, currentSequence, targetSequence, -gap)
                gap <= config.allowableGap ->
                    CatchupValid(projectionName, currentSequence)
                else ->
                    CatchupBehind(projectionName, currentSequence, targetSequence, gap)
            }
        } catch (e: Exception) {
            CatchupError(projectionName, e.message ?: "Unknown error")
        }
    }

    /**
     * Validates that one projection has caught up to another projection.
     * Useful for A/B scenarios where projection_b needs to catch up to projection_a.
     *
     * @param candidateProjectionName Name of the projection being validated (e.g., "projection_b")
     * @param targetProjectionName Name of the projection to catch up to (e.g., "projection_a")
     * @return CatchupValidationResult indicating validation status
     */
    fun validateCatchupToProjection(candidateProjectionName: String, targetProjectionName: String): CatchupValidationResult {
        return try {
            val targetBookmark = bookmarkStore.bookmarkFor(targetProjectionName)
            validateCatchup(candidateProjectionName, targetBookmark.sequence)
        } catch (e: Exception) {
            CatchupError(candidateProjectionName, "Failed to get target projection '$targetProjectionName': ${e.message}")
        }
    }

    /**
     * Validates multiple projections against their respective targets.
     *
     * @param projectionTargets Map of projection name to target sequence number
     * @return Map of projection name to validation result
     */
    fun validateMultipleCatchups(projectionTargets: Map<String, Long>): Map<String, CatchupValidationResult> {
        return projectionTargets.mapValues { (projectionName, targetSequence) ->
            validateCatchup(projectionName, targetSequence)
        }
    }

    /**
     * Validates multiple projections against a common target sequence.
     *
     * @param projectionNames List of projection names to validate
     * @param targetSequence Common target sequence all projections should reach
     * @return Map of projection name to validation result
     */
    fun validateMultipleCatchupsToSequence(projectionNames: List<String>, targetSequence: Long): Map<String, CatchupValidationResult> {
        return projectionNames.associateWith { projectionName ->
            validateCatchup(projectionName, targetSequence)
        }
    }

    /**
     * Checks if all validation results represent successful catch-up.
     *
     * @param results Collection of validation results to check
     * @return true if all results are CatchupValid, false otherwise
     */
    fun allCaughtUp(results: Collection<CatchupValidationResult>): Boolean {
        return results.all { it is CatchupValid }
    }

    /**
     * Returns summary information about catch-up validation results.
     *
     * @param results Collection of validation results to summarize
     * @return Summary string describing the validation status
     */
    fun summarizeResults(results: Collection<CatchupValidationResult>): String {
        val valid = results.count { it is CatchupValid }
        val behind = results.count { it is CatchupBehind }
        val ahead = results.count { it is CatchupAhead }
        val errors = results.count { it is CatchupError }
        val total = results.size

        return "Projection catch-up validation: $valid/$total valid, $behind behind, $ahead ahead, $errors errors"
    }
}