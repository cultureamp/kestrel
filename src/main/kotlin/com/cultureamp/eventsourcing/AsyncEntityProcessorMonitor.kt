package com.cultureamp.eventsourcing

import java.time.Duration
import java.time.Instant

/**
 * Reports how far each [AsyncEntityProcessor] is lagging behind the head of its entity table, analogous to
 * [AsyncEventProcessorMonitor]. Since positions are timestamps rather than sequence numbers, lag is measured in
 * milliseconds.
 */
class AsyncEntityProcessorMonitor(
    private val asyncEntityProcessors: List<AsyncEntityProcessor<*>>,
    private val metrics: (EntityLag) -> Unit,
    private val clock: () -> Instant = Instant::now,
) {
    fun run() {
        val lags = asyncEntityProcessors.map {
            val bookmarkPosition = it.bookmarkStore.bookmarkFor(it.bookmarkName).position
            val lastUpdatedAt = it.entityUpdatedAtStats.lastUpdatedAt()
            EntityLag(
                name = it.bookmarkName,
                bookmarkPosition = bookmarkPosition,
                lastUpdatedAt = lastUpdatedAt,
                now = clock(),
            )
        }

        lags.forEach {
            metrics(it)
        }
    }
}

/**
 * @property lagMs how far the bookmark is behind the newest row in the table. Null when there is nothing to compare:
 * either the table is empty, or the processor has not yet processed anything (in which case [hasStarted] is false and
 * the real lag is "the whole table").
 * @property latencyMs how stale the bookmark is in wall-clock terms. Unlike [lagMs] this keeps growing while a
 * processor is stuck, even if nothing new is being written, so it is usually the more useful thing to alert on.
 */
data class EntityLag(
    val name: String,
    val bookmarkPosition: EntityPosition?,
    val lastUpdatedAt: Instant?,
    val now: Instant,
) {
    val hasStarted: Boolean = bookmarkPosition != null
    val bookmarkUpdatedAt: Instant? = bookmarkPosition?.updatedAt
    val lagMs: Long? = if (bookmarkUpdatedAt != null && lastUpdatedAt != null) Duration.between(bookmarkUpdatedAt, lastUpdatedAt).toMillis() else null
    val latencyMs: Long? = bookmarkUpdatedAt?.let { Duration.between(it, now).toMillis() }
}
