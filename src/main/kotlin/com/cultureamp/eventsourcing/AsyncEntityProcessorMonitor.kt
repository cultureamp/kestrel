package com.cultureamp.eventsourcing

import org.joda.time.DateTime

/**
 * Reports how far each [AsyncEntityProcessor] is lagging behind the head of its entity table, analogous to
 * [AsyncEventProcessorMonitor]. Since positions are timestamps rather than sequence numbers, lag is measured in
 * milliseconds.
 */
class AsyncEntityProcessorMonitor(
    private val asyncEntityProcessors: List<AsyncEntityProcessor<*>>,
    private val metrics: (EntityLag) -> Unit,
    private val clock: () -> DateTime = DateTime::now,
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
    val lastUpdatedAt: DateTime?,
    val now: DateTime,
) {
    val hasStarted: Boolean = bookmarkPosition != null
    val bookmarkUpdatedAt: DateTime? = bookmarkPosition?.updatedAt
    val lagMs: Long? = if (bookmarkUpdatedAt != null && lastUpdatedAt != null) lastUpdatedAt.millis - bookmarkUpdatedAt.millis else null
    val latencyMs: Long? = bookmarkUpdatedAt?.let { now.millis - it.millis }
}
