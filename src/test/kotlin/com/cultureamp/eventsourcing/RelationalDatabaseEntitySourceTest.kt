package com.cultureamp.eventsourcing

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.Duration
import java.time.LocalDateTime
import java.util.TimeZone
import java.util.UUID

class RelationalDatabaseEntitySourceTest : DescribeSpec({
    val db = PgTestConfig.db ?: Database.connect(url = "jdbc:h2:mem:test;MODE=MySQL;DB_CLOSE_DELAY=-1;", driver = "org.h2.Driver")
    val goalRelationships = GoalRelationshipsTable()
    val baseTime = LocalDateTime.of(2026, 8, 10, 9, 0, 0, 0)
    val distantFuture = baseTime.plus(Duration.ofDays(365))
    val accountId = UUID.randomUUID()

    val entitySource = RelationalDatabaseEntitySource(
        db = db,
        table = goalRelationships,
        updatedAtColumn = goalRelationships.updatedAt,
        idColumn = goalRelationships.id,
        rowToEntity = { it[goalRelationships.id] },
    )

    fun insertGoalRelationship(
        updatedAt: LocalDateTime,
        deletedAt: LocalDateTime? = null,
        inAccount: UUID = accountId,
    ): GoalRelationship {
        val relationship = GoalRelationship(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), inAccount, baseTime, updatedAt, deletedAt)
        transaction(db) {
            goalRelationships.insert {
                it[goalRelationships.id] = relationship.id
                it[goalRelationships.childGoalId] = relationship.childGoalId
                it[goalRelationships.parentGoalId] = relationship.parentGoalId
                it[goalRelationships.accountId] = relationship.accountId
                it[goalRelationships.createdAt] = relationship.createdAt
                it[goalRelationships.updatedAt] = relationship.updatedAt
                it[goalRelationships.deletedAt] = relationship.deletedAt
                it[goalRelationships.cascadingWeight] = relationship.cascadingWeight
            }
        }
        return relationship
    }

    beforeTest {
        transaction(db) {
            SchemaUtils.create(goalRelationships)
        }
    }

    afterTest {
        transaction(db) {
            SchemaUtils.drop(goalRelationships)
        }
    }

    describe("getAfter") {
        it("returns rows in updated-at order from the beginning when there is no position") {
            val third = insertGoalRelationship(baseTime.plusSeconds(3))
            val first = insertGoalRelationship(baseTime.plusSeconds(1))
            val second = insertGoalRelationship(baseTime.plusSeconds(2))

            entitySource.getAfter(null, distantFuture).map { it.entity } shouldBe listOf(first.id, second.id, third.id)
        }

        it("returns only rows strictly after the given position") {
            insertGoalRelationship(baseTime.plusSeconds(1))
            val second = insertGoalRelationship(baseTime.plusSeconds(2))
            val third = insertGoalRelationship(baseTime.plusSeconds(3))

            entitySource.getAfter(second.position, distantFuture).map { it.entity } shouldBe listOf(third.id)
        }

        it("positions each row by its own updated-at and id") {
            val only = insertGoalRelationship(baseTime.plusSeconds(1))

            val read = entitySource.getAfter(null, distantFuture).single()

            read.position.id shouldBe only.id
            read.position.updatedAt shouldBe only.updatedAt
        }

        it("excludes rows at or after the safe boundary") {
            val old = insertGoalRelationship(baseTime.plusSeconds(1))
            insertGoalRelationship(baseTime.plusSeconds(30))

            entitySource.getAfter(null, baseTime.plusSeconds(10)).map { it.entity } shouldBe listOf(old.id)
        }

        it("excludes a row sitting exactly on the safe boundary") {
            val onTheBoundary = insertGoalRelationship(baseTime.plusSeconds(10))

            // every row of the oldest open transaction sits on exactly this value, so `<=` would admit all of them
            entitySource.getAfter(null, onTheBoundary.updatedAt).map { it.entity } shouldBe emptyList()
            entitySource.getAfter(null, onTheBoundary.updatedAt.plusNanos(1_000)).map { it.entity } shouldBe listOf(onTheBoundary.id)
        }

        it("returns at most batchSize rows") {
            val inserted = (1..5).map { insertGoalRelationship(baseTime.plusSeconds(it.toLong())) }

            entitySource.getAfter(null, distantFuture, batchSize = 2).map { it.entity } shouldBe inserted.take(2).map { it.id }
        }

        it("uses the id as a tiebreaker so rows sharing an updated-at are each returned exactly once") {
            val sharedTimestamp = baseTime.plusSeconds(1)
            val inserted = (1..5).map { insertGoalRelationship(sharedTimestamp) }

            val seen = mutableListOf<UUID>()
            var position: EntityPosition? = null
            while (true) {
                val batch = entitySource.getAfter(position, distantFuture, batchSize = 2)
                if (batch.isEmpty()) break
                seen += batch.map { it.entity }
                position = batch.last().position
            }

            seen.size shouldBe 5
            seen.toSet() shouldBe inserted.map { it.id }.toSet()
        }

        it("applies an additional filter") {
            val ours = insertGoalRelationship(baseTime.plusSeconds(1))
            insertGoalRelationship(baseTime.plusSeconds(2), inAccount = UUID.randomUUID())
            val oneAccountOnly = RelationalDatabaseEntitySource(
                db = db,
                table = goalRelationships,
                updatedAtColumn = goalRelationships.updatedAt,
                idColumn = goalRelationships.id,
                filter = { goalRelationships.accountId eq accountId },
                rowToEntity = { it[goalRelationships.id] },
            )

            oneAccountOnly.getAfter(null, distantFuture).map { it.entity } shouldBe listOf(ours.id)
        }
    }

    describe("lastUpdatedAt") {
        it("returns null when the table is empty") {
            entitySource.lastUpdatedAt() shouldBe null
        }

        it("returns the newest updated-at in the table") {
            insertGoalRelationship(baseTime.plusSeconds(1))
            insertGoalRelationship(baseTime.plusSeconds(30))
            insertGoalRelationship(baseTime.plusSeconds(2))

            entitySource.lastUpdatedAt() shouldBe baseTime.plusSeconds(30)
        }

        it("respects the filter when finding the newest row") {
            insertGoalRelationship(baseTime.plusSeconds(1))
            insertGoalRelationship(baseTime.plusSeconds(30), inAccount = UUID.randomUUID())
            val oneAccountOnly = RelationalDatabaseEntitySource(
                db = db,
                table = goalRelationships,
                updatedAtColumn = goalRelationships.updatedAt,
                idColumn = goalRelationships.id,
                filter = { goalRelationships.accountId eq accountId },
                rowToEntity = { it[goalRelationships.id] },
            )

            oneAccountOnly.lastUpdatedAt() shouldBe baseTime.plusSeconds(1)
        }
    }

    describe("Op.TRUE default filter") {
        it("reads soft-deleted rows too, which is how a deletion reaches a projection at all") {
            val live = insertGoalRelationship(baseTime.plusSeconds(1))
            val deleted = insertGoalRelationship(baseTime.plusSeconds(2), deletedAt = baseTime.plusSeconds(5))
            val unfiltered = RelationalDatabaseEntitySource(
                db = db,
                table = goalRelationships,
                updatedAtColumn = goalRelationships.updatedAt,
                idColumn = goalRelationships.id,
                filter = { Op.TRUE },
                rowToEntity = { it[goalRelationships.id] },
            )

            unfiltered.getAfter(null, distantFuture).map { it.entity } shouldBe listOf(live.id, deleted.id)
        }
    }

    describe("time zones") {
        it("reads and binds positions without going through the JVM's default zone") {
            // 02:30 on 4 October 2026 does not exist on Melbourne's clocks: DST starts at 02:00 that day. A value that
            // travels through java.sql.Timestamp is built in the default zone, so it comes out as 03:30 on read and
            // reaches the database as 03:30 on bind — which for a position is an hour of rows skipped. The row is
            // inserted as a literal so that the column holds exactly 02:30 whatever the fixture's own mapping does.
            val inTheGap = LocalDateTime.of(2026, 10, 4, 2, 30)
            val id = UUID.randomUUID()
            val defaultZone = TimeZone.getDefault()
            TimeZone.setDefault(TimeZone.getTimeZone("Australia/Melbourne"))
            try {
                transaction(db) {
                    exec(
                        "INSERT INTO goal_relationships (id, child_goal_id, parent_goal_id, account_id, created_at, updated_at, cascading_weight) " +
                            "VALUES ('$id', '${UUID.randomUUID()}', '${UUID.randomUUID()}', '$accountId', " +
                            "TIMESTAMP '2026-10-04 02:00:00', TIMESTAMP '2026-10-04 02:30:00', 0)",
                    )
                }

                val read = entitySource.getAfter(null, distantFuture).single()
                read.position shouldBe EntityPosition(inTheGap, id)
                entitySource.lastUpdatedAt() shouldBe inTheGap

                // bound the same way: a bookmark on this row selects strictly past it, not an hour past it
                entitySource.getAfter(read.position, distantFuture) shouldBe emptyList()
                entitySource.getAfter(EntityPosition(inTheGap.minusNanos(1_000), UUID(0, 0)), distantFuture).single().position shouldBe read.position
                // and a boundary sitting exactly on it excludes it, rather than admitting an hour of rows beyond it
                entitySource.getAfter(null, inTheGap) shouldBe emptyList()
            } finally {
                TimeZone.setDefault(defaultZone)
            }
        }
    }

    describe("mapping whole rows") {
        it("maps every column of the real table") {
            val inserted = insertGoalRelationship(baseTime.plusSeconds(1), deletedAt = baseTime.plusSeconds(2))
            val wholeRows = RelationalDatabaseEntitySource(
                db = db,
                table = goalRelationships,
                updatedAtColumn = goalRelationships.updatedAt,
                idColumn = goalRelationships.id,
                rowToEntity = {
                    GoalRelationship(
                        id = it[goalRelationships.id],
                        childGoalId = it[goalRelationships.childGoalId],
                        parentGoalId = it[goalRelationships.parentGoalId],
                        accountId = it[goalRelationships.accountId],
                        createdAt = it[goalRelationships.createdAt],
                        updatedAt = it[goalRelationships.updatedAt],
                        deletedAt = it[goalRelationships.deletedAt],
                        cascadingWeight = it[goalRelationships.cascadingWeight],
                    )
                },
            )

            val read = wholeRows.getAfter(null, distantFuture).single().entity

            read.id shouldBe inserted.id
            read.childGoalId shouldBe inserted.childGoalId
            read.parentGoalId shouldBe inserted.parentGoalId
            read.accountId shouldBe inserted.accountId
            read.createdAt shouldBe inserted.createdAt
            read.updatedAt shouldBe inserted.updatedAt
            read.deletedAt shouldBe inserted.deletedAt
            read.cascadingWeight.compareTo(inserted.cascadingWeight) shouldBe 0
        }
    }
})
