package com.cultureamp.eventsourcing

import com.cultureamp.common.Action
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.joda.time.DateTime
import java.util.UUID

class BatchedAsyncEntityProcessorIntegrationTest : DescribeSpec({
    val db = PgTestConfig.db ?: Database.connect(url = "jdbc:h2:mem:test;MODE=MySQL;DB_CLOSE_DELAY=-1;", driver = "org.h2.Driver")
    val bookmarksTable = EntityBookmarks("entity_bookmarks_integration")
    val bookmarkStore = RelationalDatabaseEntityBookmarkStore(db, bookmarksTable)
    val baseTime = DateTime(2026, 8, 10, 9, 0, 0, 0)
    val goalRelationships = GoalRelationshipsTable()
    val bookmarkName = "GoalRelationshipNames"

    val entitySource = RelationalDatabaseEntitySource(
        db = db,
        table = goalRelationships,
        updatedAtColumn = goalRelationships.updatedAt,
        idColumn = goalRelationships.id,
        rowToEntity = { GoalRelationship(it[goalRelationships.id], it[goalRelationships.name], it[goalRelationships.updatedAt]) },
    )

    fun insertGoalRelationship(name: String, updatedAt: DateTime): GoalRelationship {
        val relationshipId = UUID.randomUUID()
        transaction(db) {
            goalRelationships.insert {
                it[id] = relationshipId
                it[goalRelationships.name] = name
                it[retired] = false
                it[goalRelationships.updatedAt] = updatedAt
            }
        }
        return GoalRelationship(relationshipId, name, updatedAt)
    }

    fun touchGoalRelationship(relationship: GoalRelationship, updatedAt: DateTime) {
        transaction(db) {
            goalRelationships.update({ goalRelationships.id eq relationship.id }) {
                it[goalRelationships.updatedAt] = updatedAt
            }
        }
    }

    beforeTest {
        transaction(db) {
            SchemaUtils.create(goalRelationships)
            SchemaUtils.create(bookmarksTable)
        }
    }

    afterTest {
        transaction(db) {
            SchemaUtils.drop(bookmarksTable)
            SchemaUtils.drop(goalRelationships)
        }
    }

    describe("BatchedAsyncEntityProcessor against a real table") {
        it("processes a table, persists its position, and resumes from it") {
            val processed = mutableListOf<String>()
            val processor = BatchedAsyncEntityProcessor(
                entitySource = entitySource,
                entityUpdatedAtStats = entitySource,
                bookmarkStore = bookmarkStore,
                bookmarkName = bookmarkName,
                entityProcessor = EntityProcessor.from { relationship: GoalRelationship -> processed += relationship.name },
                batchSize = 2,
                clock = { baseTime.plusHours(1) },
            )
            val first = insertGoalRelationship("first", baseTime.plusSeconds(1))
            insertGoalRelationship("second", baseTime.plusSeconds(2))
            insertGoalRelationship("third", baseTime.plusSeconds(3))

            bookmarkStore.bookmarkFor(bookmarkName) shouldBe EntityBookmark(bookmarkName, EntityPosition.beginning)

            processor.processOneBatch() shouldBe Action.Continue
            processed shouldBe listOf("first", "second")
            bookmarkStore.bookmarkFor(bookmarkName).position.updatedAt.millis shouldBe baseTime.plusSeconds(2).millis

            processor.processOneBatch() shouldBe Action.Wait
            processed shouldBe listOf("first", "second", "third")

            // nothing new to do, and crucially the persisted position round-trips well enough not to re-read the last row
            processor.processOneBatch() shouldBe Action.Wait
            processed shouldBe listOf("first", "second", "third")

            // a row that gets touched is picked up again
            touchGoalRelationship(first, baseTime.plusSeconds(4))
            processor.processOneBatch() shouldBe Action.Wait
            processed shouldBe listOf("first", "second", "third", "first")
        }

        it("does not read rows that are within the timestamp delay of now") {
            val processed = mutableListOf<String>()
            var now = baseTime.plusSeconds(10)
            val processor = BatchedAsyncEntityProcessor(
                entitySource = entitySource,
                entityUpdatedAtStats = entitySource,
                bookmarkStore = bookmarkStore,
                bookmarkName = bookmarkName,
                entityProcessor = EntityProcessor.from { relationship: GoalRelationship -> processed += relationship.name },
                timestampDelayMs = 5_000,
                clock = { now },
            )
            insertGoalRelationship("settled", baseTime.plusSeconds(1))
            insertGoalRelationship("in-flight", baseTime.plusSeconds(9))

            processor.processOneBatch()
            processed shouldBe listOf("settled")

            now = baseTime.plusSeconds(20)
            processor.processOneBatch()
            processed shouldBe listOf("settled", "in-flight")
        }

        it("reports lag against the head of the table") {
            val processor = BatchedAsyncEntityProcessor(
                entitySource = entitySource,
                entityUpdatedAtStats = entitySource,
                bookmarkStore = bookmarkStore,
                bookmarkName = bookmarkName,
                entityProcessor = EntityProcessor.from { _: GoalRelationship -> },
                batchSize = 1,
                clock = { baseTime.plusHours(1) },
            )
            insertGoalRelationship("first", baseTime.plusSeconds(1))
            insertGoalRelationship("second", baseTime.plusSeconds(11))
            var lag: EntityLag? = null
            val monitor = AsyncEntityProcessorMonitor(listOf(processor), { lag = it }, clock = { baseTime.plusSeconds(31) })

            processor.processOneBatch()
            monitor.run()
            lag!!.lagMs shouldBe 10_000
            lag!!.latencyMs shouldBe 30_000

            processor.processOneBatch()
            monitor.run()
            lag!!.lagMs shouldBe 0
            lag!!.latencyMs shouldBe 20_000
        }
    }
})
