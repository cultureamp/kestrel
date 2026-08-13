package com.cultureamp.eventsourcing

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * An [EntitySource] over any table with an `updated_at`-style timestamp column and a `uuid` identifier, analogous to
 * [RelationalDatabaseEventStore] on the event-sourcing side. It also implements [EntityUpdatedAtStats], so the same
 * object can be handed to [AsyncEntityProcessorMonitor] for lag monitoring.
 *
 * For this to perform, the table wants an index on `(updatedAtColumn, idColumn)`.
 *
 * @param updatedAtColumn the column that advances whenever a row changes. Rows are read in `(updatedAt, id)` order, so
 * this column must never move backwards for a row that has already been read, or that update will be missed. It must be
 * a `timestamp with time zone`, so that a position is an absolute moment and no time-zone has to be assumed anywhere;
 * map it with Exposed's `timestampWithTimeZone`. The [OffsetDateTime] it yields converts to and from the [Instant] of an
 * [EntityPosition] losslessly, and the offset plays no part in ordering.
 * @param idColumn the tiebreaker column, used to give rows sharing an updated-at value a stable total ordering.
 * @param filter an optional additional predicate, applied to both reads and the [lastUpdatedAt] head calculation.
 * @param rowToEntity maps a row to whatever the [EntityProcessor] wants to consume.
 */
class RelationalDatabaseEntitySource<E>(
    private val db: Database,
    private val table: Table,
    private val updatedAtColumn: Column<OffsetDateTime>,
    private val idColumn: Column<UUID>,
    private val filter: () -> Op<Boolean> = { Op.TRUE },
    private val rowToEntity: (ResultRow) -> E,
) : EntitySource<E>, EntityUpdatedAtStats {

    override fun getAfter(after: EntityPosition?, safeBefore: Instant, batchSize: Int): List<PositionedEntity<E>> {
        val afterPosition = if (after != null) {
            val afterUpdatedAt = after.updatedAt.utc()
            (updatedAtColumn greater afterUpdatedAt) or ((updatedAtColumn eq afterUpdatedAt) and (idColumn greater after.id))
        } else {
            Op.TRUE
        }
        // Strictly less than, never `<=`. With `now()`-stamped timestamps every row written by the oldest open
        // transaction sits at exactly its xact_start, which is exactly the boundary a SafeBoundary reports, so `<=`
        // would admit that transaction's entire row set rather than excluding it.
        val predicate = afterPosition and (updatedAtColumn less safeBefore.utc()) and filter()
        return transaction(db) {
            table
                .selectAll()
                .where(predicate)
                .orderBy(updatedAtColumn to SortOrder.ASC, idColumn to SortOrder.ASC)
                .limit(batchSize)
                .map { row -> PositionedEntity(rowToEntity(row), EntityPosition(row[updatedAtColumn].toInstant(), row[idColumn])) }
        }
    }

    override fun lastUpdatedAt(): Instant? {
        return transaction(db) {
            table
                .select(updatedAtColumn)
                .where(filter())
                .orderBy(updatedAtColumn, SortOrder.DESC)
                .limit(1)
                .map { row -> row[updatedAtColumn].toInstant() }
                .firstOrNull()
        }
    }
}

private fun Instant.utc(): OffsetDateTime = atOffset(ZoneOffset.UTC)
