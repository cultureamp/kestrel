package com.cultureamp.eventsourcing

import java.time.LocalDateTime
import java.util.UUID

/**
 * The position of an entity row in the "updated-at stream" of a table, analogous to the global `sequence` of an
 * [Event] in the event-store.
 *
 * Because an `updated_at` column is not unique, the entity `id` is used as a tiebreaker, giving a total ordering of
 * `(updatedAt, id)`. This mirrors the "timestamp+incrementing" mode of the Confluent JDBC source connector
 * ([Incremental Query Modes](https://docs.confluent.io/kafka-connectors/jdbc/current/source-connector/overview.html#incremental-query-modes)).
 *
 * [updatedAt] is a [LocalDateTime] read from a `timestamp without time zone` column and carried around unconverted, so
 * a position means whatever the column holds. Kestrel's convention, here as in the events table, is that it holds UTC:
 * nothing in this API converts between zones, so a column stamped in local time would be compared against a UTC
 * boundary and every timestamp here would be wrong by the offset. Its natural equality agrees with [compareTo], which
 * is what bookmarks are compared on.
 *
 * [compareTo] compares `updatedAt`, then `id` as an *unsigned* 128-bit value, matching how Postgres orders the `uuid`
 * type. Databases that order UUIDs as signed (H2, for one) disagree with it for positions sharing an `updatedAt`, which
 * is harmless: ordering rows within a batch is always the database's job (see [EntitySource]), and this ordering only
 * answers "has a processor caught up?".
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

/** An entity along with its [EntityPosition], analogous to a [SequencedEvent]. */
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
 * 2. Return only rows with `updated_at < safeBefore`. See [SafeBoundary] for more detail on why this is necessary.
 * 3. Return rows ordered ascending by `(updated_at, id)`, at most [batchSize] of them.
 *
 * A [LocalDateTime] is nanosecond-precision, so it round-trips a `timestamp` exactly and a bookmark saved from a row
 * selects strictly past that row next time whatever the column's precision. A source that breaks the contract anyway
 * would stall or skip rows, so [BatchedAsyncEntityProcessor] checks each row against the bookmark it is advancing from
 * and fails loudly rather than spinning silently.
 *
 * A source must not compute `safeBefore` for itself: the boundary has to be established before the snapshot the rows
 * are read with, which is why [BatchedAsyncEntityProcessor] reads it from a [SafeBoundary] and passes it in.
 */
interface EntitySource<out E> {
    fun getAfter(after: EntityPosition?, safeBefore: LocalDateTime, batchSize: Int = 100): List<PositionedEntity<E>>

    companion object {
        /** Builds an [EntitySource] from a repository function that already knows how to position its rows. */
        fun <E> from(fetch: (EntityPosition?, LocalDateTime, Int) -> List<PositionedEntity<E>>) = object : EntitySource<E> {
            override fun getAfter(after: EntityPosition?, safeBefore: LocalDateTime, batchSize: Int) = fetch(after, safeBefore, batchSize)
        }
    }
}
