package com.cultureamp.eventsourcing

import java.time.LocalDateTime
import java.util.UUID

/**
 * The position of an entity row in the "updated-at stream" of a table, analogous to the global `sequence` of an
 * [Event] in the event-store.
 *
 * Because an `updated_at` column is not unique, the entity `id` is used as a tiebreaker, giving a total ordering of
 * `(updatedAt, id)`. This mirrors the "timestamp+incrementing" mode of the Confluent JDBC source connector.
 *
 * A position is a cursor into a table rather than a moment in time, which is why [updatedAt] is a [LocalDateTime]: it
 * is whatever the naive timestamp column holds, carried around without being reinterpreted against a time-zone, and
 * compared against other values from that same column. Its natural equality agrees with [compareTo], which matters
 * because this is what bookmarks are compared on.
 *
 * Note on ordering: [compareTo] compares `updatedAt`, then compares `id` as an *unsigned* 128-bit value, which matches
 * how Postgres orders the `uuid` type. Some other databases (H2, for example) order UUIDs using signed comparison, so
 * for positions sharing the same `updatedAt` this ordering may disagree with theirs. Ordering of rows within a batch is
 * always the database's responsibility (see [EntitySource]); this ordering is only used for the "has a processor caught
 * up?" style comparisons.
 */
data class EntityPosition(val updatedAt: LocalDateTime, val id: UUID) : Comparable<EntityPosition> {
    override fun compareTo(other: EntityPosition): Int {
        val byUpdatedAt = updatedAt.compareTo(other.updatedAt)
        return if (byUpdatedAt != 0) byUpdatedAt else id.compareUnsigned(other.id)
    }
}

private fun UUID.compareUnsigned(other: UUID): Int {
    val byMostSignificant = java.lang.Long.compareUnsigned(mostSignificantBits, other.mostSignificantBits)
    return if (byMostSignificant != 0) byMostSignificant else java.lang.Long.compareUnsigned(leastSignificantBits, other.leastSignificantBits)
}

/**
 * An entity along with its [EntityPosition], analogous to a [SequencedEvent].
 */
data class PositionedEntity<out E>(val entity: E, val position: EntityPosition)

/**
 * Reads entities from some table in `(updated_at, id)` order, analogous to an [EventSource] reading events in
 * `sequence` order.
 *
 * Implementations must honour the following contract, since [BatchedAsyncEntityProcessor] relies on it to avoid
 * skipping or infinitely reprocessing rows:
 *
 * 1. Return only rows strictly after [after] in `(updatedAt, id)` order, i.e.
 *    `updated_at > after.updatedAt OR (updated_at = after.updatedAt AND id > after.id)`. A null [after] means "from
 *    the very beginning".
 * 2. Return only rows with `updated_at <= upTo`.
 * 3. Return rows ordered ascending by `(updated_at, id)`, at most [batchSize] of them.
 *
 * A [LocalDateTime] round-trips a timestamp column exactly, down to nanoseconds, so a bookmark saved from a row selects
 * strictly past that row next time and there is no precision requirement on the column. A source that breaks the
 * contract above anyway will stall or skip rows, so [BatchedAsyncEntityProcessor] checks each row against the bookmark
 * it is advancing from and fails loudly rather than spinning silently.
 */
interface EntitySource<out E> {
    fun getAfter(after: EntityPosition?, upTo: LocalDateTime, batchSize: Int = 100): List<PositionedEntity<E>>

    companion object {
        /**
         * Builds an [EntitySource] from a repository function that already knows how to position its rows.
         */
        fun <E> from(fetch: (EntityPosition?, LocalDateTime, Int) -> List<PositionedEntity<E>>) = object : EntitySource<E> {
            override fun getAfter(after: EntityPosition?, upTo: LocalDateTime, batchSize: Int) = fetch(after, upTo, batchSize)
        }

        /**
         * Builds an [EntitySource] from a repository function that returns plain entities, deriving each row's
         * position from the entity itself via [positionOf].
         */
        fun <E> from(positionOf: (E) -> EntityPosition, fetch: (EntityPosition?, LocalDateTime, Int) -> List<E>) = object : EntitySource<E> {
            override fun getAfter(after: EntityPosition?, upTo: LocalDateTime, batchSize: Int) =
                fetch(after, upTo, batchSize).map { PositionedEntity(it, positionOf(it)) }
        }
    }
}
