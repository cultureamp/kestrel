package com.cultureamp.eventsourcing

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.EqOp
import org.jetbrains.exposed.v1.core.ExpressionWithColumnType
import org.jetbrains.exposed.v1.core.GreaterOp
import org.jetbrains.exposed.v1.core.LessOp
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.QueryParameter
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.castTo
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.select
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
 * it must never move backwards for a row already read, or that update is missed. It is a `timestamp without time zone`
 * holding UTC (see [EntityPosition]). Positions are read from it and compared against it through
 * [UtcLocalDateTimeColumnType] whatever type it is mapped with here, so Exposed's `datetime` works for positions; map
 * it with [utcDatetime] so that `rowToEntity` reads it the same zone-free way. Kestrel never writes it — see the README
 * on maintaining the polled column, since what stamps it decides whether [SafeBoundary] can be sound.
 * @param idColumn the tiebreaker column, used to give rows sharing an updated-at value a stable total ordering.
 * @param filter an optional additional predicate, applied to both reads and the [lastUpdatedAt] head calculation. Note
 * that a row this excludes does not disappear from the projection, it stops being updated in it — so do not filter on a
 * soft-delete flag if the projection publishes deletions, because a soft delete is the only way one can be seen at all.
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

    private val positionType = UtcLocalDateTimeColumnType()

    /**
     * The polled column read through Kestrel's zone-free type rather than whatever type the consumer mapped it with.
     * The `CAST` to the column's own type is a no-op to the database, and is what gives the expression an identity of
     * its own: Exposed keys result columns by their rendered SQL, so an expression rendering identically to the column
     * would be folded into it and read with the consumer's type.
     */
    private val positionUpdatedAt: ExpressionWithColumnType<LocalDateTime> = updatedAtColumn.castTo<LocalDateTime>(positionType)

    override fun getAfter(after: EntityPosition?, safeBefore: LocalDateTime, batchSize: Int): List<PositionedEntity<E>> {
        val afterPosition = if (after != null) {
            GreaterOp(updatedAtColumn, utc(after.updatedAt)) or (EqOp(updatedAtColumn, utc(after.updatedAt)) and (idColumn greater after.id))
        } else {
            Op.TRUE
        }
        val predicate = afterPosition and LessOp(updatedAtColumn, utc(safeBefore)) and filter()
        return transaction(db) {
            table
                .select(table.columns + positionUpdatedAt)
                .where(predicate)
                .orderBy(updatedAtColumn to SortOrder.ASC, idColumn to SortOrder.ASC)
                .limit(batchSize)
                .map { row -> PositionedEntity(rowToEntity(row), EntityPosition(row[positionUpdatedAt], row[idColumn])) }
        }
    }

    override fun lastUpdatedAt(): LocalDateTime? {
        return transaction(db) {
            table
                .select(positionUpdatedAt)
                .where(filter())
                .orderBy(updatedAtColumn, SortOrder.DESC)
                .limit(1)
                .map { row -> row[positionUpdatedAt] }
                .firstOrNull()
        }
    }

    /** A position bound through the zone-free type, whatever type [updatedAtColumn] carries. */
    private fun utc(value: LocalDateTime) = QueryParameter(value, positionType)
}
