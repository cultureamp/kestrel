package com.cultureamp.eventsourcing

import org.joda.time.DateTime
import java.util.UUID

/**
 * The position of an entity row in the "updated-at stream" of a table, analogous to the global `sequence` of an
 * [Event] in the event-store.
 *
 * Because an `updated_at` column is not unique, the entity `id` is used as a tiebreaker, giving a total ordering of
 * `(updatedAt, id)`. This mirrors the "timestamp+incrementing" mode of the Confluent JDBC source connector.
 *
 * Note on ordering: [compareTo] compares `updatedAt` on milliseconds, then compares `id` as an *unsigned* 128-bit
 * value, which matches how Postgres orders the `uuid` type. Some other databases (H2, for example) order UUIDs using
 * signed comparison, so for positions sharing the same `updatedAt` this ordering may disagree with theirs. Ordering of
 * rows within a batch is always the database's responsibility (see [EntitySource]); this ordering is only used for the
 * "has a processor caught up?" style comparisons.
 */
data class EntityPosition(val updatedAt: DateTime, val id: UUID) : Comparable<EntityPosition> {
    override fun compareTo(other: EntityPosition): Int {
        val byUpdatedAt = updatedAt.millis.compareTo(other.updatedAt.millis)
        return if (byUpdatedAt != 0) byUpdatedAt else id.compareUnsigned(other.id)
    }

    /**
     * Equality is on the millisecond instant rather than on the joda [DateTime] itself, whose own equality also
     * compares chronology and time-zone. Two positions read back from different connections can differ in those
     * without being different positions, and equality has to agree with [compareTo] here, since this is what
     * bookmarks are compared on.
     */
    override fun equals(other: Any?) = other is EntityPosition && updatedAt.millis == other.updatedAt.millis && id == other.id

    override fun hashCode() = 31 * updatedAt.millis.hashCode() + id.hashCode()

    companion object {
        /**
         * The position before any row, i.e. "start from the beginning of the table". It is the [EntityPosition]
         * equivalent of a [Bookmark] with sequence `0`, and is what an [EntityBookmarkStore] hands back for a bookmark
         * it has never seen before.
         *
         * The instant is the unix epoch and the id is the nil UUID, so a row would have to predate 1970 to be missed
         * by it.
         */
        val beginning = EntityPosition(DateTime(0), UUID(0, 0))
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
 *    `updated_at > after.updatedAt OR (updated_at = after.updatedAt AND id > after.id)`. [EntityPosition.beginning]
 *    means "from the very beginning".
 * 2. Return only rows with `updated_at <= upTo`.
 * 3. Return rows ordered ascending by `(updated_at, id)`, at most [batchSize] of them.
 *
 * There is one pitfall worth calling out: if the underlying `updated_at` column has sub-millisecond precision (a
 * Postgres `timestamp` has microsecond precision) then the value read into a joda [DateTime] is truncated, and a
 * bookmark saved from it will keep re-selecting that same row forever. Either store `updated_at` with millisecond
 * precision, or have the source truncate the column in both the projection and the predicate.
 * [BatchedAsyncEntityProcessor] detects the resulting stall and fails loudly rather than spinning silently.
 */
interface EntitySource<out E> {
    fun getAfter(after: EntityPosition, upTo: DateTime, batchSize: Int = 100): List<PositionedEntity<E>>

    companion object {
        /**
         * Builds an [EntitySource] from a repository function that already knows how to position its rows.
         */
        fun <E> from(fetch: (EntityPosition, DateTime, Int) -> List<PositionedEntity<E>>) = object : EntitySource<E> {
            override fun getAfter(after: EntityPosition, upTo: DateTime, batchSize: Int) = fetch(after, upTo, batchSize)
        }

        /**
         * Builds an [EntitySource] from a repository function that returns plain entities, deriving each row's
         * position from the entity itself via [positionOf].
         */
        fun <E> from(positionOf: (E) -> EntityPosition, fetch: (EntityPosition, DateTime, Int) -> List<E>) = object : EntitySource<E> {
            override fun getAfter(after: EntityPosition, upTo: DateTime, batchSize: Int) =
                fetch(after, upTo, batchSize).map { PositionedEntity(it, positionOf(it)) }
        }
    }
}
