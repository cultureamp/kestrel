package com.cultureamp.eventsourcing

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.jodatime.datetime
import org.joda.time.DateTime
import java.math.BigDecimal
import java.util.UUID

/**
 * Mirrors the real table:
 *
 * ```
 * CREATE TABLE public.goal_relationships (
 *     id                uuid NOT NULL,
 *     child_goal_id     uuid NOT NULL,
 *     parent_goal_id    uuid NOT NULL,
 *     account_id        uuid NOT NULL,
 *     created_at        timestamp without time zone NOT NULL,
 *     updated_at        timestamp without time zone NOT NULL,
 *     deleted_at        timestamp without time zone,
 *     cascading_weight  numeric(5,4) DEFAULT 0 NOT NULL
 * );
 * ```
 */
class GoalRelationshipsTable(tableName: String = "goal_relationships") : Table(tableName) {
    val id = javaUUID("id")
    val childGoalId = javaUUID("child_goal_id")
    val parentGoalId = javaUUID("parent_goal_id")
    val accountId = javaUUID("account_id")
    val createdAt = datetime("created_at")
    val updatedAt = datetime("updated_at")
    val deletedAt = datetime("deleted_at").nullable()
    val cascadingWeight = decimal("cascading_weight", precision = 5, scale = 4).default(BigDecimal("0.0000"))
    override val primaryKey = PrimaryKey(id)
}

data class GoalRelationship(
    val id: UUID,
    val childGoalId: UUID,
    val parentGoalId: UUID,
    val accountId: UUID,
    val createdAt: DateTime,
    val updatedAt: DateTime,
    val deletedAt: DateTime? = null,
    val cascadingWeight: BigDecimal = BigDecimal("0.0000"),
) {
    val position = EntityPosition(updatedAt, id)
}

class InMemoryEntityBookmarkStore(private val lockObtainable: Boolean = true) : EntityBookmarkStore {
    private val positions = mutableMapOf<String, EntityPosition>()
    val saved = mutableListOf<EntityBookmark>()

    override fun bookmarkFor(bookmarkName: String) = EntityBookmark(bookmarkName, positions[bookmarkName])

    override fun bookmarksFor(bookmarkNames: Set<String>) = bookmarkNames.map { bookmarkFor(it) }.toSet()

    override fun save(bookmarkName: String, position: EntityPosition) {
        positions[bookmarkName] = position
        saved += EntityBookmark(bookmarkName, position)
    }

    override fun checkoutBookmark(bookmarkName: String): Either<LockNotObtained, EntityBookmark> =
        if (lockObtainable) Right(bookmarkFor(bookmarkName)) else Left(LockNotObtained)
}

/**
 * A well-behaved [EntitySource] over a fixed list of rows, honouring the ordering, `after` and `upTo` contract.
 */
class InMemoryEntitySource<E>(private val rows: List<PositionedEntity<E>>) : EntitySource<E>, EntityUpdatedAtStats {
    override fun getAfter(after: EntityPosition?, upTo: DateTime, batchSize: Int) = rows
        .sortedBy { it.position }
        .filter { (after == null || it.position > after) && !it.position.updatedAt.isAfter(upTo) }
        .take(batchSize)

    override fun lastUpdatedAt() = rows.maxByOrNull { it.position }?.position?.updatedAt
}
