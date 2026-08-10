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
    val goalRelationships = GoalRelationshipsTable()
    val bookmarksTable = EntityBookmarks("entity_bookmarks_integration")
    val bookmarkStore = RelationalDatabaseEntityBookmarkStore(db, bookmarksTable)
    val baseTime = DateTime(2026, 8, 10, 9, 0, 0, 0)
    val accountId = UUID.randomUUID()
    val bookmarkName = "GoalRelationshipProjector"

    // the real table has no updated_at, so it is polled by created_at
    val entitySource = RelationalDatabaseEntitySource(
        db = db,
        table = goalRelationships,
        updatedAtColumn = goalRelationships.createdAt,
        idColumn = goalRelationships.id,
        rowToEntity = { it[goalRelationships.id] },
    )

    fun insertGoalRelationship(createdAt: DateTime): GoalRelationship {
        val relationship = GoalRelationship(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), accountId, createdAt)
        transaction(db) {
            goalRelationships.insert {
                it[goalRelationships.id] = relationship.id
                it[goalRelationships.childGoalId] = relationship.childGoalId
                it[goalRelationships.parentGoalId] = relationship.parentGoalId
                it[goalRelationships.accountId] = relationship.accountId
                it[goalRelationships.createdAt] = relationship.createdAt
                it[goalRelationships.cascadingWeight] = relationship.cascadingWeight
            }
        }
        return relationship
    }

    fun touchGoalRelationship(relationship: GoalRelationship, createdAt: DateTime) {
        transaction(db) {
            goalRelationships.update({ goalRelationships.id eq relationship.id }) {
                it[goalRelationships.createdAt] = createdAt
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
            val processed = mutableListOf<UUID>()
            val processor = BatchedAsyncEntityProcessor(
                entitySource = entitySource,
                entityUpdatedAtStats = entitySource,
                bookmarkStore = bookmarkStore,
                bookmarkName = bookmarkName,
                entityProcessor = EntityProcessor.from { id: UUID -> processed += id },
                batchSize = 2,
                clock = { baseTime.plusHours(1) },
            )
            val first = insertGoalRelationship(baseTime.plusSeconds(1))
            val second = insertGoalRelationship(baseTime.plusSeconds(2))
            val third = insertGoalRelationship(baseTime.plusSeconds(3))

            bookmarkStore.bookmarkFor(bookmarkName) shouldBe EntityBookmark(bookmarkName, null)

            processor.processOneBatch() shouldBe Action.Continue
            processed shouldBe listOf(first.id, second.id)
            bookmarkStore.bookmarkFor(bookmarkName).position shouldBe second.position

            processor.processOneBatch() shouldBe Action.Wait
            processed shouldBe listOf(first.id, second.id, third.id)

            // nothing new to do, and crucially the persisted position round-trips well enough not to re-read the last row
            processor.processOneBatch() shouldBe Action.Wait
            processed shouldBe listOf(first.id, second.id, third.id)

            // a row whose polled column moves forward is picked up again
            touchGoalRelationship(first, baseTime.plusSeconds(4))
            processor.processOneBatch() shouldBe Action.Wait
            processed shouldBe listOf(first.id, second.id, third.id, first.id)
        }

        it("does not read rows that are within the timestamp delay of now") {
            val processed = mutableListOf<UUID>()
            var now = baseTime.plusSeconds(10)
            val processor = BatchedAsyncEntityProcessor(
                entitySource = entitySource,
                entityUpdatedAtStats = entitySource,
                bookmarkStore = bookmarkStore,
                bookmarkName = bookmarkName,
                entityProcessor = EntityProcessor.from { id: UUID -> processed += id },
                timestampDelayMs = 5_000,
                clock = { now },
            )
            val settled = insertGoalRelationship(baseTime.plusSeconds(1))
            val inFlight = insertGoalRelationship(baseTime.plusSeconds(9))

            processor.processOneBatch()
            processed shouldBe listOf(settled.id)

            now = baseTime.plusSeconds(20)
            processor.processOneBatch()
            processed shouldBe listOf(settled.id, inFlight.id)
        }

        it("reports lag against the head of the table") {
            val processor = BatchedAsyncEntityProcessor(
                entitySource = entitySource,
                entityUpdatedAtStats = entitySource,
                bookmarkStore = bookmarkStore,
                bookmarkName = bookmarkName,
                entityProcessor = EntityProcessor.from { _: UUID -> },
                batchSize = 1,
                clock = { baseTime.plusHours(1) },
            )
            insertGoalRelationship(baseTime.plusSeconds(1))
            insertGoalRelationship(baseTime.plusSeconds(11))
            var lag: EntityLag? = null
            val monitor = AsyncEntityProcessorMonitor(listOf(processor), { lag = it }, clock = { baseTime.plusSeconds(31) })

            monitor.run()
            lag!!.hasStarted shouldBe false

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
