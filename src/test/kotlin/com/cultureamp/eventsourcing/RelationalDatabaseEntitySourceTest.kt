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
    val goalRelationships = GoalRelationshipsTable("goal_relationships")
    val baseTime = DateTime(2026, 8, 10, 9, 0, 0, 0)
    val distantFuture = baseTime.plusYears(1)

    val entitySource = RelationalDatabaseEntitySource(
        db = db,
        table = goalRelationships,
        updatedAtColumn = goalRelationships.updatedAt,
        idColumn = goalRelationships.id,
        rowToEntity = { GoalRelationship(it[goalRelationships.id], it[goalRelationships.name], it[goalRelationships.updatedAt]) },
    )

    fun insertGoalRelationship(name: String, updatedAt: DateTime, retired: Boolean = false): GoalRelationship {
        val relationshipId = UUID.randomUUID()
        transaction(db) {
            goalRelationships.insert {
                it[id] = relationshipId
                it[goalRelationships.name] = name
                it[goalRelationships.retired] = retired
                it[goalRelationships.updatedAt] = updatedAt
            }
        }
        return GoalRelationship(relationshipId, name, updatedAt)
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
            insertGoalRelationship("third", baseTime.plusSeconds(3))
            insertGoalRelationship("first", baseTime.plusSeconds(1))
            insertGoalRelationship("second", baseTime.plusSeconds(2))

            entitySource.getAfter(EntityPosition.beginning, distantFuture).map { it.entity.name } shouldBe listOf("first", "second", "third")
        }

        it("returns only rows strictly after the given position") {
            insertGoalRelationship("first", baseTime.plusSeconds(1))
            val second = insertGoalRelationship("second", baseTime.plusSeconds(2))
            insertGoalRelationship("third", baseTime.plusSeconds(3))

            entitySource.getAfter(second.position, distantFuture).map { it.entity.name } shouldBe listOf("third")
        }

        it("positions each row by its own updated-at and id") {
            val only = insertGoalRelationship("only", baseTime.plusSeconds(1))

            val read = entitySource.getAfter(EntityPosition.beginning, distantFuture).single()

            read.position.id shouldBe only.id
            read.position.updatedAt.millis shouldBe only.updatedAt.millis
        }

        it("excludes rows updated after the upTo cutoff") {
            insertGoalRelationship("old", baseTime.plusSeconds(1))
            insertGoalRelationship("new", baseTime.plusSeconds(30))

            entitySource.getAfter(EntityPosition.beginning, baseTime.plusSeconds(10)).map { it.entity.name } shouldBe listOf("old")
        }

        it("returns at most batchSize rows") {
            (1..5).forEach { insertGoalRelationship("relationship-$it", baseTime.plusSeconds(it)) }

            entitySource.getAfter(EntityPosition.beginning, distantFuture, batchSize = 2).map { it.entity.name } shouldBe listOf("relationship-1", "relationship-2")
        }

        it("uses the id as a tiebreaker so rows sharing an updated-at are each returned exactly once") {
            val sharedTimestamp = baseTime.plusSeconds(1)
            val inserted = (1..5).map { insertGoalRelationship("relationship-$it", sharedTimestamp) }

            val seen = mutableListOf<String>()
            var position = EntityPosition.beginning
            while (true) {
                val batch = entitySource.getAfter(position, distantFuture, batchSize = 2)
                if (batch.isEmpty()) break
                seen += batch.map { it.entity.name }
                position = batch.last().position
            }

            seen.size shouldBe 5
            seen.toSet() shouldBe inserted.map { it.name }.toSet()
        }

        it("applies an additional filter") {
            insertGoalRelationship("live", baseTime.plusSeconds(1))
            insertGoalRelationship("retired", baseTime.plusSeconds(2), retired = true)
            val liveOnly = RelationalDatabaseEntitySource(
                db = db,
                table = goalRelationships,
                updatedAtColumn = goalRelationships.updatedAt,
                idColumn = goalRelationships.id,
                filter = { goalRelationships.retired eq false },
                rowToEntity = { it[goalRelationships.name] },
            )

            liveOnly.getAfter(EntityPosition.beginning, distantFuture).map { it.entity } shouldBe listOf("live")
        }
    }

    describe("lastUpdatedAt") {
        it("returns the beginning of time when the table is empty") {
            entitySource.lastUpdatedAt().millis shouldBe EntityPosition.beginning.updatedAt.millis
        }

        it("returns the newest updated-at in the table") {
            insertGoalRelationship("first", baseTime.plusSeconds(1))
            insertGoalRelationship("newest", baseTime.plusSeconds(30))
            insertGoalRelationship("second", baseTime.plusSeconds(2))

            entitySource.lastUpdatedAt().millis shouldBe baseTime.plusSeconds(30).millis
        }

        it("respects the filter when finding the newest row") {
            insertGoalRelationship("live", baseTime.plusSeconds(1))
            insertGoalRelationship("retired", baseTime.plusSeconds(30), retired = true)
            val liveOnly = RelationalDatabaseEntitySource(
                db = db,
                table = goalRelationships,
                updatedAtColumn = goalRelationships.updatedAt,
                idColumn = goalRelationships.id,
                filter = { goalRelationships.retired eq false },
                rowToEntity = { it[goalRelationships.name] },
            )

            liveOnly.lastUpdatedAt().millis shouldBe baseTime.plusSeconds(1).millis
        }
    }

    describe("Op.TRUE default filter") {
        it("reads everything by default") {
            insertGoalRelationship("live", baseTime.plusSeconds(1))
            insertGoalRelationship("retired", baseTime.plusSeconds(2), retired = true)
            val unfiltered = RelationalDatabaseEntitySource(
                db = db,
                table = goalRelationships,
                updatedAtColumn = goalRelationships.updatedAt,
                idColumn = goalRelationships.id,
                filter = { Op.TRUE },
                rowToEntity = { it[goalRelationships.name] },
            )

            unfiltered.getAfter(EntityPosition.beginning, distantFuture).map { it.entity } shouldBe listOf("live", "retired")
        }
    }
})
