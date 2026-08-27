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
import java.time.LocalDateTime
import java.util.UUID

/**
 * An [EntitySource] over any table with an `updated_at`-style timestamp column and a `uuid` identifier, analogous to
 * [RelationalDatabaseEventStore] on the event-sourcing side. It also implements [EntityUpdatedAtStats], so the same
 * object can be handed to [AsyncEntityProcessorMonitor] for lag monitoring.
 *
 * For this to perform, the table wants an index on `(updatedAtColumn, idColumn)`.
 *
 * @param updatedAtColumn the column that advances whenever a row changes. Rows are read in `(updatedAt, id)` order, so
 * it must never move backwards for a row already read, or that update is missed. Map it with Exposed's `datetime`: it
 * is a `timestamp without time zone` holding UTC, read and written unconverted (see [EntityPosition]).
 * @param idColumn the tiebreaker column, used to give rows sharing an updated-at value a stable total ordering.
 * @param filter an optional additional predicate, applied to both reads and the [lastUpdatedAt] head calculation.
 * @param rowToEntity maps a row to whatever the [EntityProcessor] wants to consume.
 */
class RelationalDatabaseEntitySource<E>(
    private val db: Database,
    private val table: Table,
    private val updatedAtColumn: Column<LocalDateTime>,
    private val idColumn: Column<UUID>,
    private val filter: () -> Op<Boolean> = { Op.TRUE },
    private val rowToEntity: (ResultRow) -> E,
) : EntitySource<E>, EntityUpdatedAtStats {

    override fun getAfter(after: EntityPosition?, safeBefore: LocalDateTime, batchSize: Int): List<PositionedEntity<E>> {
        val afterPosition = if (after != null) {
            (updatedAtColumn greater after.updatedAt) or ((updatedAtColumn eq after.updatedAt) and (idColumn greater after.id))
        } else {
            Op.TRUE
        }
        val predicate = afterPosition and (updatedAtColumn less safeBefore) and filter()
        return transaction(db) {
            table
                .selectAll()
                .where(predicate)
                .orderBy(updatedAtColumn to SortOrder.ASC, idColumn to SortOrder.ASC)
                .limit(batchSize)
                .map { row -> PositionedEntity(rowToEntity(row), EntityPosition(row[updatedAtColumn], row[idColumn])) }
        }
    }

    override fun lastUpdatedAt(): LocalDateTime? {
        return transaction(db) {
            table
                .select(updatedAtColumn)
                .where(filter())
                .orderBy(updatedAtColumn, SortOrder.DESC)
                .limit(1)
                .map { row -> row[updatedAtColumn] }
                .firstOrNull()
        }
    }
}
