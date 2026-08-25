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
 * committing behind the bookmark and being skipped forever. [SafeBoundary] explains why an `updated_at` column needs
 * one at all; [PostgresXactStartSafeBoundary] is the implementation to use on Postgres. There is no default because no
 * one value is safe for every table.
 * @param stallThreshold how long the [safeBoundary] may hold this processor back before it throws
 * [SafeBoundaryStalledException] into the caller's [ExponentialBackoff] `onFailure`, and so into error tracking rather
 * than onto a graph nobody is watching. Null disables the check.
 * @param clock used only to measure how long a stall has lasted, never to decide which rows are read — the boundary is
 * database-generated so that no application clock can affect correctness. Reads UTC, like every clock in this API.
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

        // Read the boundary before the rows, in its own transaction, so a row committing in between is excluded rather
        // than skipped. SafeBoundary has the argument, including why merging the two reads is not safe.
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

        if (count > 0) heldBackSince = null else failIfHeldBackTooLong(safeBefore)

        return if (count >= batchSize) Action.Continue else Action.Wait
    }

    /** When this processor was first observed making no progress *because of* the boundary. Null when it is not. */
    private var heldBackSince: LocalDateTime? = null

    /**
     * Tells "held back by the boundary" from "caught up", which is the whole difficulty here. Staleness of the boundary
     * cannot be the signal: during a first run over a large table it may sit hours in the past, pinned by an unrelated
     * transaction, while the processor works through millions of rows perfectly happily — throwing there would abort
     * the backfill. What discriminates is the head of the table against the boundary. Reading nothing while the newest
     * row sits at or beyond it means there is work the boundary forbids; reading nothing while the newest row is behind
     * it means there is nothing to do. Only continuously, too: any batch with rows in it clears the timer.
     */
    private fun failIfHeldBackTooLong(safeBefore: LocalDateTime) {
        val head = entityUpdatedAtStats.lastUpdatedAt()
        if (head == null || head < safeBefore) {
            // caught up: everything in the table is already behind the boundary
            heldBackSince = null
            return
        }

        val now = clock()
        val since = heldBackSince ?: now.also { heldBackSince = it }
        val heldBackFor = Duration.between(since, now)

        if (stallThreshold != null && heldBackFor > stallThreshold) {
            throw SafeBoundaryStalledException(
                "$bookmarkName has been held back by its safe boundary for $heldBackFor, which is longer than the " +
                    "$stallThreshold threshold. Rows are waiting ($head is at or beyond the boundary of $safeBefore) " +
                    "but cannot be read until the oldest open transaction ends. Nothing is lost and this recovers on " +
                    "its own once that happens, so the fix is to end that transaction. " +
                    safeBoundary.describeBlockers(),
            )
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

open class EntitySourceException(message: String) : RuntimeException(message)
class EntitySourceStalledException(message: String) : EntitySourceException(message)
class EntitySourceOrderingException(message: String) : EntitySourceException(message)

interface EntityStatisticsCollector {
    fun entityProcessed(processor: AsyncEntityProcessor<*>, entity: PositionedEntity<*>, durationMs: Long)
}
