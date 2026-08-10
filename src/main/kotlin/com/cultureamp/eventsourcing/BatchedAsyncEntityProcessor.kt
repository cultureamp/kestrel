package com.cultureamp.eventsourcing

import com.cultureamp.common.Action
import org.joda.time.DateTime
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
 * @param timestampDelayMs how long to wait after a row's `updated_at` before considering it readable, equivalent to
 * the JDBC connector's `timestamp.delay.interval.ms`. This exists because `updated_at` is typically stamped when a
 * transaction *starts* but only becomes visible when it *commits*, so a row with an earlier `updated_at` can appear
 * after we have already moved the bookmark past it. Only rows with `updated_at <= now - timestampDelayMs` are read,
 * which trades this much processing latency for not silently skipping those rows. Set it comfortably longer than the
 * longest transaction that writes to the table.
 * @param clock the source of "now", exposed for testing.
 */
class BatchedAsyncEntityProcessor<E>(
    override val entitySource: EntitySource<E>,
    override val entityUpdatedAtStats: EntityUpdatedAtStats,
    override val bookmarkStore: EntityBookmarkStore,
    override val bookmarkName: String,
    override val entityProcessor: EntityProcessor<E>,
    private val batchSize: Int = 1000,
    private val timestampDelayMs: Long = 1000,
    private val clock: () -> DateTime = DateTime::now,
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

        // hold back rows younger than the delay, so that a row committing late can't slip in behind the bookmark
        val upTo = clock().minus(timestampDelayMs)

        val (count, finalBookmark) = entitySource.getAfter(startBookmark.position, upTo, batchSize).foldIndexed(
            0 to startBookmark,
        ) { index, (_, bookmark), positionedEntity ->
            validateProgress(bookmark.position, positionedEntity.position)
            processEntity(positionedEntity)
            val updatedBookmark = bookmark.copy(position = positionedEntity.position)
            bookmarkStore.save(updatedBookmark)
            index + 1 to updatedBookmark
        }

        endLog(count, finalBookmark)

        return if (count >= batchSize) Action.Continue else Action.Wait
    }

    /**
     * The source is contractually required to return rows strictly after the given position, in ascending order. If it
     * doesn't we would either silently skip rows or, worse, reprocess the same row forever without the bookmark ever
     * advancing, so fail loudly instead. The most likely cause is an `updated_at` column with sub-millisecond
     * precision being truncated on its way into a joda `DateTime`.
     */
    private fun validateProgress(previous: EntityPosition?, next: EntityPosition) {
        if (previous == null) return
        if (next == previous) {
            throw EntitySourceStalledException(
                "Entity-source for $bookmarkName returned the row at position $next that its bookmark is already at. " +
                    "If the source's updated-at column has sub-millisecond precision, its value is being truncated and " +
                    "this row will be re-read forever.",
            )
        }
        if (next.updatedAt.millis < previous.updatedAt.millis) {
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
