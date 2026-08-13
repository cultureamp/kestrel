package com.cultureamp.eventsourcing

import com.cultureamp.common.Action
import kotlin.random.Random

interface BookmarkedEntityProcessor<E> {
    val bookmarkStore: EntityBookmarkStore
    val bookmarkName: String
    val entityProcessor: EntityProcessor<E>

    companion object {
        fun <E> from(bookmarkStore: EntityBookmarkStore, bookmarkName: String, entityProcessor: EntityProcessor<E>) = object : BookmarkedEntityProcessor<E> {
            override val bookmarkStore = bookmarkStore
            override val bookmarkName = bookmarkName
            override val entityProcessor = entityProcessor
        }
    }
}

interface AsyncEntityProcessor<E> : BookmarkedEntityProcessor<E> {
    val entitySource: EntitySource<E>
    val entityUpdatedAtStats: EntityUpdatedAtStats
}

/**
 * Reads rows from an [EntitySource] in `(updated_at, id)` order, dispatches them to an [EntityProcessor], and records
 * progress as an [EntityPosition] in an [EntityBookmarkStore]. It is the entity-table equivalent of
 * [BatchedAsyncEventProcessor], and behaves like the "timestamp+incrementing" mode of the Confluent JDBC source
 * connector.
 *
 * Run it the same way as a [BatchedAsyncEventProcessor]:
 *
 * ```
 * ExponentialBackoff(onFailure = { e, _ -> logger.error(e) }).run { asyncEntityProcessor.processOneBatch() }
 * ```
 *
 * @param safeBoundary establishes the timestamp below which rows are safe to read. This is the mechanism that stops a
 * slow transaction's rows from committing behind the bookmark and being skipped forever; [SafeBoundary] explains why an
 * `updated_at` column needs one at all, and [PostgresXactStartSafeBoundary] is the implementation to use on Postgres.
 * It has no default deliberately — there is no value that is safe for every table, so the choice has to be made
 * explicitly.
 */
class BatchedAsyncEntityProcessor<E>(
    override val entitySource: EntitySource<E>,
    override val entityUpdatedAtStats: EntityUpdatedAtStats,
    override val bookmarkStore: EntityBookmarkStore,
    override val bookmarkName: String,
    override val entityProcessor: EntityProcessor<E>,
    private val safeBoundary: SafeBoundary,
    private val batchSize: Int = 1000,
    private val startLog: (EntityBookmark) -> Unit = { bookmark ->
        System.out.println("Polling for entities for ${bookmark.name} from position ${bookmark.position}")
    },
    private val endLog: (Int, EntityBookmark) -> Unit = { count, bookmark ->
        if (count > 0 || Random.nextFloat() < 0.01) {
            System.out.println("Finished processing batch for ${bookmark.name}, $count entities up to position ${bookmark.position}")
        }
    },
    private val stats: EntityStatisticsCollector? = null,
) : AsyncEntityProcessor<E> {

    fun processOneBatch(): Action {
        val startBookmark = bookmarkStore.checkoutBookmark(bookmarkName).let {
            if (it is Left<LockNotObtained>)
                // try again shortly
                return Action.Wait
            else
                (it as Right).value
        }

        startLog(startBookmark)

        // Establish the boundary BEFORE reading rows, and in its own transaction. A row that commits between this call
        // and the read below is excluded by the boundary rather than skipped: see SafeBoundary for why the ordering
        // matters, and why merging this into the source's read transaction to save a round trip is not safe.
        val safeBefore = safeBoundary.safeBefore()

        val (count, finalBookmark) = entitySource.getAfter(startBookmark.position, safeBefore, batchSize).foldIndexed(
            0 to startBookmark,
        ) { index, (_, bookmark), positionedEntity ->
            validateProgress(bookmark.position, positionedEntity.position)
            processEntity(positionedEntity)
            bookmarkStore.save(bookmarkName, positionedEntity.position)
            index + 1 to bookmark.copy(position = positionedEntity.position)
        }

        endLog(count, finalBookmark)

        return if (count >= batchSize) Action.Continue else Action.Wait
    }

    /**
     * The source is contractually required to return rows strictly after the given position, in ascending order. If it
     * doesn't we would either silently skip rows or, worse, reprocess the same row forever without the bookmark ever
     * advancing, so fail loudly instead. A position round-trips exactly, so a source that respects its `after`
     * predicate cannot trip these; they guard against one that doesn't.
     */
    private fun validateProgress(previous: EntityPosition?, next: EntityPosition) {
        if (previous == null) return
        if (next == previous) {
            throw EntitySourceStalledException(
                "Entity-source for $bookmarkName returned the row at position $next that its bookmark is already at, " +
                    "rather than only rows strictly after it, so this row would be re-read forever.",
            )
        }
        if (next.updatedAt < previous.updatedAt) {
            throw EntitySourceOrderingException(
                "Entity-source for $bookmarkName returned the row at position $next after the row at position $previous, " +
                    "but rows must be returned in ascending updated-at order.",
            )
        }
    }

    private fun processEntity(positionedEntity: PositionedEntity<E>) {
        stats?.let {
            val startTime = System.currentTimeMillis()
            entityProcessor.process(positionedEntity.entity, positionedEntity.position)
            stats.entityProcessed(this, positionedEntity, System.currentTimeMillis() - startTime)
        } ?: entityProcessor.process(positionedEntity.entity, positionedEntity.position)
    }
}

open class EntitySourceException(message: String) : RuntimeException(message)
class EntitySourceStalledException(message: String) : EntitySourceException(message)
class EntitySourceOrderingException(message: String) : EntitySourceException(message)

interface EntityStatisticsCollector {
    fun entityProcessed(processor: AsyncEntityProcessor<*>, entity: PositionedEntity<*>, durationMs: Long)
}
