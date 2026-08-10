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

    fun relationship(name: String, secondsAfterBase: Int, id: UUID = UUID.randomUUID()) =
        GoalRelationship(id, name, baseTime.plusSeconds(secondsAfterBase))

    fun positioned(relationship: GoalRelationship) = PositionedEntity(relationship, relationship.position)

    fun processorFor(
        relationships: List<GoalRelationship>,
        processed: MutableList<GoalRelationship>,
        bookmarkStore: EntityBookmarkStore,
        batchSize: Int = 1000,
        timestampDelayMs: Long = 1000,
        clock: () -> DateTime = wellAfterAnyRow,
    ) = BatchedAsyncEntityProcessor(
        entitySource = InMemoryEntitySource(relationships.map(::positioned)),
        entityUpdatedAtStats = InMemoryEntitySource(relationships.map(::positioned)),
        bookmarkStore = bookmarkStore,
        bookmarkName = "goal-relationships",
        entityProcessor = EntityProcessor.from { relationship: GoalRelationship -> processed += relationship },
        batchSize = batchSize,
        timestampDelayMs = timestampDelayMs,
        clock = clock,
        startLog = {},
        endLog = { _, _ -> },
    )

    describe("processOneBatch") {
        it("processes rows in updated-at order and bookmarks each one") {
            val first = relationship("first", 1)
            val second = relationship("second", 2)
            val third = relationship("third", 3)
            val processed = mutableListOf<GoalRelationship>()
            val bookmarkStore = InMemoryEntityBookmarkStore()

            // deliberately out of order in the source, which is expected to sort them
            val action = processorFor(listOf(third, first, second), processed, bookmarkStore).processOneBatch()

            processed.map { it.name } shouldBe listOf("first", "second", "third")
            bookmarkStore.saved.map { it.position } shouldBe listOf(first.position, second.position, third.position)
            bookmarkStore.bookmarkFor("goal-relationships") shouldBe EntityBookmark("goal-relationships", third.position)
            action shouldBe Action.Wait
        }

        it("starts from an existing bookmark rather than the beginning") {
            val first = relationship("first", 1)
            val second = relationship("second", 2)
            val processed = mutableListOf<GoalRelationship>()
            val bookmarkStore = InMemoryEntityBookmarkStore()
            bookmarkStore.save(EntityBookmark("goal-relationships", first.position))

            processorFor(listOf(first, second), processed, bookmarkStore).processOneBatch()

            processed.map { it.name } shouldBe listOf("second")
        }

        it("tiebreaks on id so that rows sharing an updated-at are each processed exactly once across batches") {
            val sameSecond = (1..5).map { relationship("relationship-$it", 1) }
            val processed = mutableListOf<GoalRelationship>()
            val bookmarkStore = InMemoryEntityBookmarkStore()
            val processor = processorFor(sameSecond, processed, bookmarkStore, batchSize = 2)

            processor.processOneBatch() shouldBe Action.Continue
            processor.processOneBatch() shouldBe Action.Continue
            processor.processOneBatch() shouldBe Action.Wait
            processor.processOneBatch() shouldBe Action.Wait

            processed.map { it.name }.toSet() shouldBe sameSecond.map { it.name }.toSet()
            processed.size shouldBe 5
        }

        it("does not read rows younger than the timestamp delay, and picks them up once they age") {
            val old = relationship("old", 0)
            val fresh = relationship("fresh", 10)
            val processed = mutableListOf<GoalRelationship>()
            val bookmarkStore = InMemoryEntityBookmarkStore()
            var now = fresh.updatedAt.plusMillis(500)

            val processor = processorFor(listOf(old, fresh), processed, bookmarkStore, timestampDelayMs = 1000, clock = { now })

            processor.processOneBatch()
            processed.map { it.name } shouldBe listOf("old")

            now = fresh.updatedAt.plusMillis(1500)
            processor.processOneBatch()
            processed.map { it.name } shouldBe listOf("old", "fresh")
        }

        it("returns Continue when a full batch was processed, so the caller comes straight back") {
            val relationships = (1..3).map { relationship("relationship-$it", it) }
            val processed = mutableListOf<GoalRelationship>()
            val processor = processorFor(relationships, processed, InMemoryEntityBookmarkStore(), batchSize = 3)

            processor.processOneBatch() shouldBe Action.Continue
        }

        it("waits without processing anything when the bookmark lock can't be obtained") {
            val processed = mutableListOf<GoalRelationship>()
            val bookmarkStore = InMemoryEntityBookmarkStore(lockObtainable = false)

            val action = processorFor(listOf(relationship("first", 1)), processed, bookmarkStore).processOneBatch()

            action shouldBe Action.Wait
            processed shouldBe emptyList()
            bookmarkStore.saved shouldBe emptyList()
        }

        it("fails loudly when the source re-returns the row the bookmark is already at") {
            val stuck = relationship("stuck", 1)
            val bookmarkStore = InMemoryEntityBookmarkStore()
            bookmarkStore.save(EntityBookmark("goal-relationships", stuck.position))
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
            val earlier = relationship("earlier", 1)
            val later = relationship("later", 2)
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
            val relationships = (1..2).map { relationship("relationship-$it", it) }
            val collected = mutableListOf<PositionedEntity<*>>()
            val stats = object : EntityStatisticsCollector {
                override fun entityProcessed(processor: AsyncEntityProcessor<*>, entity: PositionedEntity<*>, durationMs: Long) {
                    collected += entity
                }
            }
            BatchedAsyncEntityProcessor(
                entitySource = InMemoryEntitySource(relationships.map(::positioned)),
                entityUpdatedAtStats = InMemoryEntitySource(relationships.map(::positioned)),
                bookmarkStore = InMemoryEntityBookmarkStore(),
                bookmarkName = "goal-relationships",
                entityProcessor = EntityProcessor.from { _: GoalRelationship -> },
                clock = wellAfterAnyRow,
                startLog = {},
                endLog = { _, _ -> },
                stats = stats,
            ).processOneBatch()

            collected.map { it.position } shouldBe relationships.map { it.position }
        }
    }

    describe("EntityProcessor.compose") {
        it("shares one bookmark across several processors") {
            val relationships = (1..2).map { relationship("relationship-$it", it) }
            val first = mutableListOf<String>()
            val second = mutableListOf<EntityPosition>()
            val bookmarkStore = InMemoryEntityBookmarkStore()

            BatchedAsyncEntityProcessor(
                entitySource = InMemoryEntitySource(relationships.map(::positioned)),
                entityUpdatedAtStats = InMemoryEntitySource(relationships.map(::positioned)),
                bookmarkStore = bookmarkStore,
                bookmarkName = "goal-relationships",
                entityProcessor = EntityProcessor.compose(
                    EntityProcessor.from { relationship: GoalRelationship -> first += relationship.name },
                    EntityProcessor.from { _: GoalRelationship, position: EntityPosition -> second += position },
                ),
                clock = wellAfterAnyRow,
                startLog = {},
                endLog = { _, _ -> },
            ).processOneBatch()

            first shouldBe listOf("relationship-1", "relationship-2")
            second shouldBe relationships.map { it.position }
            bookmarkStore.bookmarkFor("goal-relationships").position shouldBe relationships.last().position
        }
    }
})
