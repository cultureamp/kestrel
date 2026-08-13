package com.cultureamp.eventsourcing

import com.cultureamp.common.Action
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID

class BatchedAsyncEntityProcessorIntegrationTest : DescribeSpec({
    val db = PgTestConfig.db ?: Database.connect(url = "jdbc:h2:mem:test;MODE=MySQL;DB_CLOSE_DELAY=-1;", driver = "org.h2.Driver")
    val goalRelationships = GoalRelationshipsTable()
    val bookmarksTable = EntityBookmarks("entity_bookmarks_integration")
    val bookmarkStore = RelationalDatabaseEntityBookmarkStore(db, bookmarksTable)
    val baseTime = Instant.parse("2026-08-10T09:00:00Z")

    // created_at is naive in the real table, so it needs its own non-instant value
    val baseCreatedAt = LocalDateTime.of(2026, 8, 10, 9, 0, 0, 0)
    val accountId = UUID.randomUUID()
    val bookmarkName = "GoalRelationshipProjector"

    val entitySource = RelationalDatabaseEntitySource(
        db = db,
        table = goalRelationships,
        updatedAtColumn = goalRelationships.updatedAt,
        idColumn = goalRelationships.id,
        rowToEntity = { it[goalRelationships.id] },
    )

    fun insertGoalRelationship(updatedAt: Instant): GoalRelationship {
        val relationship = GoalRelationship(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), accountId, baseCreatedAt, updatedAt)
        transaction(db) {
            goalRelationships.insert {
                it[goalRelationships.id] = relationship.id
                it[goalRelationships.childGoalId] = relationship.childGoalId
                it[goalRelationships.parentGoalId] = relationship.parentGoalId
                it[goalRelationships.accountId] = relationship.accountId
                it[goalRelationships.createdAt] = relationship.createdAt
                it[goalRelationships.updatedAt] = relationship.updatedAt.atOffset(ZoneOffset.UTC)
                it[goalRelationships.cascadingWeight] = relationship.cascadingWeight
            }
        }
        return relationship
    }

    fun touchGoalRelationship(relationship: GoalRelationship, updatedAt: Instant) {
        transaction(db) {
            goalRelationships.update({ goalRelationships.id eq relationship.id }) {
                it[goalRelationships.updatedAt] = updatedAt.atOffset(ZoneOffset.UTC)
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
                safeBoundary = SafeBoundary { baseTime.plus(Duration.ofHours(1)) },
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

            // a row that gets touched is picked up again
            touchGoalRelationship(first, baseTime.plusSeconds(4))
            processor.processOneBatch() shouldBe Action.Wait
            processed shouldBe listOf(first.id, second.id, third.id, first.id)
        }

        it("round-trips a sub-millisecond updated-at exactly, so the bookmarked row is not re-read") {
            val processed = mutableListOf<UUID>()
            val processor = BatchedAsyncEntityProcessor(
                entitySource = entitySource,
                entityUpdatedAtStats = entitySource,
                bookmarkStore = bookmarkStore,
                bookmarkName = bookmarkName,
                entityProcessor = EntityProcessor.from { id: UUID -> processed += id },
                safeBoundary = SafeBoundary { baseTime.plus(Duration.ofHours(1)) },
            )
            // 123.456ms past the second: representable in a Postgres `timestamp`, but truncated to 123ms by a
            // millisecond-precision type, which would leave the bookmark behind the row and re-select it forever
            val subMillisecond = insertGoalRelationship(baseTime.plusSeconds(1).plusNanos(123_456_000))

            processor.processOneBatch()
            processed shouldBe listOf(subMillisecond.id)
            bookmarkStore.bookmarkFor(bookmarkName).position shouldBe subMillisecond.position

            processor.processOneBatch() shouldBe Action.Wait
            processed shouldBe listOf(subMillisecond.id)
        }

        it("does not read rows at or beyond the safe boundary") {
            val processed = mutableListOf<UUID>()
            var now = baseTime.plusSeconds(10)
            val processor = BatchedAsyncEntityProcessor(
                entitySource = entitySource,
                entityUpdatedAtStats = entitySource,
                bookmarkStore = bookmarkStore,
                bookmarkName = bookmarkName,
                entityProcessor = EntityProcessor.from { id: UUID -> processed += id },
                safeBoundary = SafeBoundary.unsafeFixedDelay(Duration.ofSeconds(5)) { now },
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
                safeBoundary = SafeBoundary { baseTime.plus(Duration.ofHours(1)) },
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
