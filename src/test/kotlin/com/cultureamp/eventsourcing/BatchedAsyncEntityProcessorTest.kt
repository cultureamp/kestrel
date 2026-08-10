package com.cultureamp.eventsourcing

import com.cultureamp.common.Action
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import org.joda.time.DateTime
import java.util.UUID

class BatchedAsyncEntityProcessorTest : DescribeSpec({
    val baseTime = DateTime(2026, 8, 10, 9, 0, 0, 0)
    val wellAfterAnyRow = { baseTime.plusHours(1) }
    val accountId = UUID.randomUUID()

    fun goalRelationship(secondsAfterBase: Int, id: UUID = UUID.randomUUID()) =
        GoalRelationship(id, UUID.randomUUID(), UUID.randomUUID(), accountId, createdAt = baseTime, updatedAt = baseTime.plusSeconds(secondsAfterBase))

    fun positioned(goalRelationship: GoalRelationship) = PositionedEntity(goalRelationship, goalRelationship.position)

    fun processorFor(
        goalRelationships: List<GoalRelationship>,
        processed: MutableList<GoalRelationship>,
        bookmarkStore: EntityBookmarkStore,
        batchSize: Int = 1000,
        timestampDelayMs: Long = 1000,
        clock: () -> DateTime = wellAfterAnyRow,
    ) = BatchedAsyncEntityProcessor(
        entitySource = InMemoryEntitySource(goalRelationships.map(::positioned)),
        entityUpdatedAtStats = InMemoryEntitySource(goalRelationships.map(::positioned)),
        bookmarkStore = bookmarkStore,
        bookmarkName = "goal-relationships",
        entityProcessor = EntityProcessor.from { goalRelationship: GoalRelationship -> processed += goalRelationship },
        batchSize = batchSize,
        timestampDelayMs = timestampDelayMs,
        clock = clock,
        startLog = {},
        endLog = { _, _ -> },
    )

    describe("processOneBatch") {
        it("processes rows in updated-at order and bookmarks each one") {
            val first = goalRelationship(1)
            val second = goalRelationship(2)
            val third = goalRelationship(3)
            val processed = mutableListOf<GoalRelationship>()
            val bookmarkStore = InMemoryEntityBookmarkStore()

            // deliberately out of order in the source, which is expected to sort them
            val action = processorFor(listOf(third, first, second), processed, bookmarkStore).processOneBatch()

            processed shouldBe listOf(first, second, third)
            bookmarkStore.saved.map { it.position } shouldBe listOf(first.position, second.position, third.position)
            bookmarkStore.bookmarkFor("goal-relationships") shouldBe EntityBookmark("goal-relationships", third.position)
            action shouldBe Action.Wait
        }

        it("starts from an existing bookmark rather than the beginning") {
            val first = goalRelationship(1)
            val second = goalRelationship(2)
            val processed = mutableListOf<GoalRelationship>()
            val bookmarkStore = InMemoryEntityBookmarkStore()
            bookmarkStore.save("goal-relationships", first.position)

            processorFor(listOf(first, second), processed, bookmarkStore).processOneBatch()

            processed shouldBe listOf(second)
        }

        it("tiebreaks on id so that rows sharing an updated-at are each processed exactly once across batches") {
            val sameSecond = (1..5).map { goalRelationship(1) }
            val processed = mutableListOf<GoalRelationship>()
            val bookmarkStore = InMemoryEntityBookmarkStore()
            val processor = processorFor(sameSecond, processed, bookmarkStore, batchSize = 2)

            processor.processOneBatch() shouldBe Action.Continue
            processor.processOneBatch() shouldBe Action.Continue
            processor.processOneBatch() shouldBe Action.Wait
            processor.processOneBatch() shouldBe Action.Wait

            processed.toSet() shouldBe sameSecond.toSet()
            processed.size shouldBe 5
        }

        it("does not read rows younger than the timestamp delay, and picks them up once they age") {
            val old = goalRelationship(0)
            val fresh = goalRelationship(10)
            val processed = mutableListOf<GoalRelationship>()
            val bookmarkStore = InMemoryEntityBookmarkStore()
            var now = fresh.updatedAt.plusMillis(500)

            val processor = processorFor(listOf(old, fresh), processed, bookmarkStore, timestampDelayMs = 1000, clock = { now })

            processor.processOneBatch()
            processed shouldBe listOf(old)

            now = fresh.updatedAt.plusMillis(1500)
            processor.processOneBatch()
            processed shouldBe listOf(old, fresh)
        }

        it("returns Continue when a full batch was processed, so the caller comes straight back") {
            val goalRelationships = (1..3).map { goalRelationship(it) }
            val processed = mutableListOf<GoalRelationship>()
            val processor = processorFor(goalRelationships, processed, InMemoryEntityBookmarkStore(), batchSize = 3)

            processor.processOneBatch() shouldBe Action.Continue
        }

        it("waits without processing anything when the bookmark lock can't be obtained") {
            val processed = mutableListOf<GoalRelationship>()
            val bookmarkStore = InMemoryEntityBookmarkStore(lockObtainable = false)

            val action = processorFor(listOf(goalRelationship(1)), processed, bookmarkStore).processOneBatch()

            action shouldBe Action.Wait
            processed shouldBe emptyList()
            bookmarkStore.saved shouldBe emptyList()
        }

        it("fails loudly when the source re-returns the row the bookmark is already at") {
            val stuck = goalRelationship(1)
            val bookmarkStore = InMemoryEntityBookmarkStore()
            bookmarkStore.save("goal-relationships", stuck.position)
            val processor = BatchedAsyncEntityProcessor(
                entitySource = EntitySource.from { _, _, _ -> listOf(PositionedEntity(stuck, stuck.position)) },
                entityUpdatedAtStats = EntityUpdatedAtStats.from { stuck.updatedAt },
                bookmarkStore = bookmarkStore,
                bookmarkName = "goal-relationships",
                entityProcessor = EntityProcessor.from { _: GoalRelationship -> },
                clock = wellAfterAnyRow,
                startLog = {},
                endLog = { _, _ -> },
            )

            shouldThrow<EntitySourceStalledException> { processor.processOneBatch() }
        }

        it("fails loudly when the source returns rows out of order") {
            val earlier = goalRelationship(1)
            val later = goalRelationship(2)
            val processor = BatchedAsyncEntityProcessor(
                entitySource = EntitySource.from { _, _, _ ->
                    listOf(PositionedEntity(later, later.position), PositionedEntity(earlier, earlier.position))
                },
                entityUpdatedAtStats = EntityUpdatedAtStats.from { later.updatedAt },
                bookmarkStore = InMemoryEntityBookmarkStore(),
                bookmarkName = "goal-relationships",
                entityProcessor = EntityProcessor.from { _: GoalRelationship -> },
                clock = wellAfterAnyRow,
                startLog = {},
                endLog = { _, _ -> },
            )

            shouldThrow<EntitySourceOrderingException> { processor.processOneBatch() }
        }

        it("reports statistics for each entity processed") {
            val goalRelationships = (1..2).map { goalRelationship(it) }
            val collected = mutableListOf<PositionedEntity<*>>()
            val stats = object : EntityStatisticsCollector {
                override fun entityProcessed(processor: AsyncEntityProcessor<*>, entity: PositionedEntity<*>, durationMs: Long) {
                    collected += entity
                }
            }
            BatchedAsyncEntityProcessor(
                entitySource = InMemoryEntitySource(goalRelationships.map(::positioned)),
                entityUpdatedAtStats = InMemoryEntitySource(goalRelationships.map(::positioned)),
                bookmarkStore = InMemoryEntityBookmarkStore(),
                bookmarkName = "goal-relationships",
                entityProcessor = EntityProcessor.from { _: GoalRelationship -> },
                clock = wellAfterAnyRow,
                startLog = {},
                endLog = { _, _ -> },
                stats = stats,
            ).processOneBatch()

            collected.map { it.position } shouldBe goalRelationships.map { it.position }
        }
    }

    describe("EntityProcessor.compose") {
        it("shares one bookmark across several processors") {
            val goalRelationships = (1..2).map { goalRelationship(it) }
            val first = mutableListOf<UUID>()
            val second = mutableListOf<EntityPosition>()
            val bookmarkStore = InMemoryEntityBookmarkStore()

            BatchedAsyncEntityProcessor(
                entitySource = InMemoryEntitySource(goalRelationships.map(::positioned)),
                entityUpdatedAtStats = InMemoryEntitySource(goalRelationships.map(::positioned)),
                bookmarkStore = bookmarkStore,
                bookmarkName = "goal-relationships",
                entityProcessor = EntityProcessor.compose(
                    EntityProcessor.from { goalRelationship: GoalRelationship -> first += goalRelationship.id },
                    EntityProcessor.from { _: GoalRelationship, position: EntityPosition -> second += position },
                ),
                clock = wellAfterAnyRow,
                startLog = {},
                endLog = { _, _ -> },
            ).processOneBatch()

            first shouldBe goalRelationships.map { it.id }
            second shouldBe goalRelationships.map { it.position }
            bookmarkStore.bookmarkFor("goal-relationships").position shouldBe goalRelationships.last().position
        }
    }
})
