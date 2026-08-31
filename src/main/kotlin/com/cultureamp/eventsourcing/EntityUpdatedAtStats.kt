package com.cultureamp.eventsourcing

import java.time.LocalDateTime

/**
 * Reports the head of an entity table's "updated-at stream", i.e. the largest `updated_at` of any row, or null when the
 * table is empty. The [EntityPosition] equivalent of [EventsSequenceStats], so that [AsyncEntityProcessorMonitor] can
 * work out how far behind the head a bookmark is.
 */
interface EntityUpdatedAtStats {
    fun lastUpdatedAt(): LocalDateTime?

    companion object {
        fun from(fetch: () -> LocalDateTime?) = object : EntityUpdatedAtStats {
            override fun lastUpdatedAt() = fetch()
        }
    }
}
