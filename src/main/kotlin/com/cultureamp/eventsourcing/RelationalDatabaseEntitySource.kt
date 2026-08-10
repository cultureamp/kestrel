package com.cultureamp.eventsourcing

import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.joda.time.DateTime
import java.util.UUID

/**
 * An [EntitySource] over any table with an `updated_at`-style timestamp column and a `uuid` identifier, analogous to
 * [RelationalDatabaseEventStore] on the event-sourcing side. It also implements [EntityUpdatedAtStats], so the same
 * object can be handed to [AsyncEntityProcessorMonitor] for lag monitoring.
 *
 * For this to perform, the table wants an index on `(updatedAtColumn, idColumn)`.
 *
 * @param updatedAtColumn the column that advances whenever a row changes. Rows are read in `(updatedAt, id)` order, so
 * this column must never move backwards for a row that has already been read, or that update will be missed.
 * @param idColumn the tiebreaker column, used to give rows sharing an updated-at value a stable total ordering.
 * @param filter an optional additional predicate, applied to both reads and the [lastUpdatedAt] head calculation.
 * @param rowToEntity maps a row to whatever the [EntityProcessor] wants to consume.
 */
class RelationalDatabaseEntitySource<E>(
    private val db: Database,
    private val table: Table,
    private val updatedAtColumn: Column<DateTime>,
    private val idColumn: Column<UUID>,
    private val filter: SqlExpressionBuilder.() -> Op<Boolean> = { Op.TRUE },
    private val rowToEntity: (ResultRow) -> E,
) : EntitySource<E>, EntityUpdatedAtStats {

    override fun getAfter(after: EntityPosition?, upTo: DateTime, batchSize: Int): List<PositionedEntity<E>> {
        val afterPosition = SqlExpressionBuilder.run {
            if (after != null) {
                (updatedAtColumn greater after.updatedAt) or ((updatedAtColumn eq after.updatedAt) and (idColumn greater after.id))
            } else {
                Op.TRUE
            }
        }
        val predicate = SqlExpressionBuilder.run { afterPosition and (updatedAtColumn lessEq upTo) and SqlExpressionBuilder.filter() }
        return transaction(db) {
            table
                .select(predicate)
                .orderBy(updatedAtColumn to SortOrder.ASC, idColumn to SortOrder.ASC)
                .limit(batchSize)
                .map { row -> PositionedEntity(rowToEntity(row), EntityPosition(row[updatedAtColumn], row[idColumn])) }
        }
    }

    override fun lastUpdatedAt(): DateTime? {
        return transaction(db) {
            table
                .slice(updatedAtColumn)
                .select(SqlExpressionBuilder.filter())
                .orderBy(updatedAtColumn, SortOrder.DESC)
                .limit(1)
                .map { row -> row[updatedAtColumn] }
                .firstOrNull()
        }
    }
}
