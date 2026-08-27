package com.cultureamp.eventsourcing

import com.cultureamp.common.Action
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneOffset
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
 * progress as an [EntityPosition] in an [EntityBookmarkStore] — the entity-table equivalent of
 * [BatchedAsyncEventProcessor]. It behaves like the
 * [`timestamp+incrementing` mode](https://docs.confluent.io/kafka-connectors/jdbc/current/source-connector/overview.html#incremental-query-modes)
 * of the Confluent JDBC source connector: a timestamp column to order by, plus an id to break ties within one
 * timestamp, since a timestamp column is not unique.
 *
 * Run it the same way as a [BatchedAsyncEventProcessor]:
 *
 * ```
 * ExponentialBackoff(onFailure = { e, _ -> logger.error(e) }).run { asyncEntityProcessor.processOneBatch() }
 * ```
 *
 * @param safeBoundary the timestamp below which rows are safe to read, which is what stops a slow transaction's rows
 * committing behind the bookmark and being skipped forever.
 * @param stallThreshold how long the oldest open transaction may hold the boundary back before [stallBehaviour] is
 * invoked. Null disables the check.
 * @param stallBehaviour whether a stall throws or is logged; see [StallBehaviour]. Either way the report comes after
 * the batch has been processed and bookmarked, so it never costs progress that was available.
 * @param clock used only to size a stall, never to decide which rows are read — the boundary is database-generated so
 * that no application clock can affect correctness. Reads UTC, like every clock in this API.
 */
class BatchedAsyncEntityProcessor<E>(
    override val entitySource: EntitySource<E>,
    override val entityUpdatedAtStats: EntityUpdatedAtStats,
    override val bookmarkStore: EntityBookmarkStore,
    override val bookmarkName: String,
    override val entityProcessor: EntityProcessor<E>,
    private val safeBoundary: SafeBoundary,
    private val batchSize: Int = 1000,
    private val stallThreshold: Duration? = Duration.ofHours(1),
    private val stallBehaviour: StallBehaviour = StallBehaviour.Throw,
    private val clock: () -> LocalDateTime = { LocalDateTime.now(ZoneOffset.UTC) },
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

        // Read the boundary before the rows, in its own transaction, so a row committing in between is excluded rather than skipped
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

        reportIfStalled(safeBefore)

        return if (count >= batchSize) Action.Continue else Action.Wait
    }

    /**
     * The boundary *is* the start time of the oldest open transaction, or the database's own clock when nothing is
     * open, so how long that transaction has been holding rows back is just the gap between the boundary and now. No
     * state to keep and no extra query.
     *
     * This is the one place an application clock meets a database timestamp, which the row-reading path deliberately
     * never does. It only sizes a stall against an hour-scale threshold, where seconds of skew do not matter.
     */
    private fun reportIfStalled(safeBefore: LocalDateTime) {
        if (stallThreshold == null) return
        val heldBackFor = Duration.between(safeBefore, clock())
        if (heldBackFor <= stallThreshold) return

        val message = "$bookmarkName's safe boundary is $heldBackFor behind now, which is longer than the " +
            "$stallThreshold threshold, because the oldest open transaction in this database has been open at least " +
            "that long. Rows at or after the boundary of $safeBefore cannot be read until it ends. Nothing is lost " +
            "and this recovers on its own once that happens, so the fix is to end that transaction. " +
            safeBoundary.describeBlockers()

        when (stallBehaviour) {
            is StallBehaviour.Throw -> throw SafeBoundaryStalledException(message)
            is StallBehaviour.LogAndContinue -> stallBehaviour.log(message)
        }
    }

    /**
     * A source that returns rows out of order, or re-returns the row its bookmark is on, would silently skip rows or
     * reprocess one forever without the bookmark advancing. A position round-trips exactly, so a source honouring its
     * `after` predicate cannot trip these.
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

/**
 * What a [BatchedAsyncEntityProcessor] does once its `safeBoundary` has been holding rows back for longer than its
 * `stallThreshold`. Both modes report the same message, which names the sessions to go and close.
 */
sealed interface StallBehaviour {
    /**
     * Throw [SafeBoundaryStalledException], so the caller's [ExponentialBackoff] `onFailure` puts it in front of
     * somebody with a stacktrace. The default: a processor publishing nothing for hours is the problem the boundary
     * exists to remove, and it should not be left to be noticed on a graph.
     */
    object Throw : StallBehaviour

    /**
     * Report and carry on. The mode for a first run over a large table, where the boundary can legitimately sit hours
     * in the past — pinned by an unrelated transaction — while millions of rows behind it are read perfectly happily,
     * and an error every poll would be noise rather than news.
     */
    data class LogAndContinue(val log: (String) -> Unit) : StallBehaviour
}

open class EntitySourceException(message: String) : RuntimeException(message)
class EntitySourceStalledException(message: String) : EntitySourceException(message)
class EntitySourceOrderingException(message: String) : EntitySourceException(message)

interface EntityStatisticsCollector {
    fun entityProcessed(processor: AsyncEntityProcessor<*>, entity: PositionedEntity<*>, durationMs: Long)
}
