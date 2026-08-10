package com.cultureamp.eventsourcing

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.joda.time.DateTime
import java.util.UUID

class RelationalDatabaseEntitySourceTest : DescribeSpec({
    val db = PgTestConfig.db ?: Database.connect(url = "jdbc:h2:mem:test;MODE=MySQL;DB_CLOSE_DELAY=-1;", driver = "org.h2.Driver")
    val goalRelationships = GoalRelationshipsTable()
    val baseTime = DateTime(2026, 8, 10, 9, 0, 0, 0)
    val distantFuture = baseTime.plusYears(1)
    val accountId = UUID.randomUUID()

    val entitySource = RelationalDatabaseEntitySource(
        db = db,
        table = goalRelationships,
        updatedAtColumn = goalRelationships.updatedAt,
        idColumn = goalRelationships.id,
        rowToEntity = { it[goalRelationships.id] },
    )

    fun insertGoalRelationship(updatedAt: DateTime, deletedAt: DateTime? = null): GoalRelationship {
        val relationship = GoalRelationship(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), accountId, baseTime, updatedAt, deletedAt)
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
            read.position.updatedAt.millis shouldBe only.updatedAt.millis
        }

        it("excludes rows updated after the upTo cutoff") {
            val old = insertGoalRelationship(baseTime.plusSeconds(1))
            insertGoalRelationship(baseTime.plusSeconds(30))

            entitySource.getAfter(null, baseTime.plusSeconds(10)).map { it.entity } shouldBe listOf(old.id)
        }

        it("returns at most batchSize rows") {
            val inserted = (1..5).map { insertGoalRelationship(baseTime.plusSeconds(it)) }

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
            val live = insertGoalRelationship(baseTime.plusSeconds(1))
            insertGoalRelationship(baseTime.plusSeconds(2), deletedAt = baseTime.plusSeconds(5))
            val liveOnly = RelationalDatabaseEntitySource(
                db = db,
                table = goalRelationships,
                updatedAtColumn = goalRelationships.updatedAt,
                idColumn = goalRelationships.id,
                filter = { goalRelationships.deletedAt.isNull() },
                rowToEntity = { it[goalRelationships.id] },
            )

            liveOnly.getAfter(null, distantFuture).map { it.entity } shouldBe listOf(live.id)
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

            entitySource.lastUpdatedAt()?.millis shouldBe baseTime.plusSeconds(30).millis
        }

        it("respects the filter when finding the newest row") {
            insertGoalRelationship(baseTime.plusSeconds(1))
            insertGoalRelationship(baseTime.plusSeconds(30), deletedAt = baseTime.plusSeconds(40))
            val liveOnly = RelationalDatabaseEntitySource(
                db = db,
                table = goalRelationships,
                updatedAtColumn = goalRelationships.updatedAt,
                idColumn = goalRelationships.id,
                filter = { goalRelationships.deletedAt.isNull() },
                rowToEntity = { it[goalRelationships.id] },
            )

            liveOnly.lastUpdatedAt()?.millis shouldBe baseTime.plusSeconds(1).millis
        }
    }

    describe("Op.TRUE default filter") {
        it("reads everything by default") {
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
            read.createdAt.millis shouldBe inserted.createdAt.millis
            read.updatedAt.millis shouldBe inserted.updatedAt.millis
            read.deletedAt?.millis shouldBe inserted.deletedAt?.millis
            read.cascadingWeight.compareTo(inserted.cascadingWeight) shouldBe 0
        }
    }
})
