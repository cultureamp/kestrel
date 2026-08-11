package com.cultureamp.eventsourcing

import java.time.LocalDateTime

/**
 * Reports the head of an entity table's "updated-at stream", i.e. the largest `updated_at` of any row. This is the
 * [EntityPosition] equivalent of [EventsSequenceStats], and exists so that [AsyncEntityProcessorMonitor] can work out
 * how far behind the head a processor's bookmark is.
 *
 * Returns null when the table has no rows at all.
 */
interface EntityUpdatedAtStats {
    fun lastUpdatedAt(): LocalDateTime?

    companion object {
        fun from(fetch: () -> LocalDateTime?) = object : EntityUpdatedAtStats {
            override fun lastUpdatedAt() = fetch()
        }
    }
}
