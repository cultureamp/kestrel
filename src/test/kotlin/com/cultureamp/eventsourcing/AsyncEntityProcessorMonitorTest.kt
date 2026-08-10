package com.cultureamp.eventsourcing

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import org.joda.time.DateTime
import java.util.UUID

class AsyncEntityProcessorMonitorTest : DescribeSpec({
    val baseTime = DateTime(2026, 8, 10, 9, 0, 0, 0)

    fun relationship(name: String, secondsAfterBase: Int) = GoalRelationship(UUID.randomUUID(), name, baseTime.plusSeconds(secondsAfterBase))

    fun monitoredProcessor(relationships: List<GoalRelationship>, bookmarkStore: EntityBookmarkStore, batchSize: Int = 1000): BatchedAsyncEntityProcessor<GoalRelationship> {
        val source = InMemoryEntitySource(relationships.map { PositionedEntity(it, it.position) })
        return BatchedAsyncEntityProcessor(
            entitySource = source,
            entityUpdatedAtStats = source,
            bookmarkStore = bookmarkStore,
            bookmarkName = "goal-relationships",
            entityProcessor = EntityProcessor.from { _: GoalRelationship -> },
            batchSize = batchSize,
            clock = { baseTime.plusHours(1) },
            startLog = {},
            endLog = { _, _ -> },
        )
    }

    describe("run") {
        it("reports the whole table as lag when nothing has been processed yet") {
            val relationships = listOf(relationship("first", 1), relationship("last", 61))
            val processor = monitoredProcessor(relationships, InMemoryEntityBookmarkStore())
            var lag: EntityLag? = null
            val monitor = AsyncEntityProcessorMonitor(listOf(processor), { lag = it }, clock = { baseTime.plusSeconds(120) })

            monitor.run()

            lag!!.name shouldBe "goal-relationships"
            lag!!.bookmarkPosition shouldBe EntityPosition.beginning
            lag!!.lastUpdatedAt.millis shouldBe baseTime.plusSeconds(61).millis
            lag!!.lagMs shouldBe baseTime.plusSeconds(61).millis - EntityPosition.beginning.updatedAt.millis
            lag!!.latencyMs shouldBe baseTime.plusSeconds(120).millis - EntityPosition.beginning.updatedAt.millis
        }

        it("reports how far behind the head of the table a partially caught-up processor is") {
            val relationships = listOf(relationship("first", 1), relationship("last", 61))
            val processor = monitoredProcessor(relationships, InMemoryEntityBookmarkStore(), batchSize = 1)
            var lag: EntityLag? = null
            val monitor = AsyncEntityProcessorMonitor(listOf(processor), { lag = it }, clock = { baseTime.plusSeconds(120) })

            processor.processOneBatch()
            monitor.run()

            lag!!.bookmarkUpdatedAt.millis shouldBe baseTime.plusSeconds(1).millis
            lag!!.lagMs shouldBe 60_000
            lag!!.latencyMs shouldBe 119_000
        }

        it("reports zero lag once caught up") {
            val relationships = listOf(relationship("first", 1), relationship("last", 61))
            val processor = monitoredProcessor(relationships, InMemoryEntityBookmarkStore())
            var lag: EntityLag? = null
            val monitor = AsyncEntityProcessorMonitor(listOf(processor), { lag = it }, clock = { baseTime.plusSeconds(120) })

            processor.processOneBatch()
            monitor.run()

            lag!!.lagMs shouldBe 0
            lag!!.latencyMs shouldBe 59_000
        }

        it("reports zero lag for an empty table") {
            val processor = monitoredProcessor(emptyList(), InMemoryEntityBookmarkStore())
            var lag: EntityLag? = null
            val monitor = AsyncEntityProcessorMonitor(listOf(processor), { lag = it }, clock = { baseTime })

            monitor.run()

            lag!!.lastUpdatedAt.millis shouldBe EntityPosition.beginning.updatedAt.millis
            lag!!.lagMs shouldBe 0
        }

        it("reports on every processor it is given") {
            val processors = listOf("a", "b").map { name ->
                BatchedAsyncEntityProcessor(
                    entitySource = InMemoryEntitySource(emptyList<PositionedEntity<GoalRelationship>>()),
                    entityUpdatedAtStats = EntityUpdatedAtStats.from { baseTime },
                    bookmarkStore = InMemoryEntityBookmarkStore(),
                    bookmarkName = name,
                    entityProcessor = EntityProcessor.from { _: GoalRelationship -> },
                    startLog = {},
                    endLog = { _, _ -> },
                )
            }
            val lags = mutableListOf<EntityLag>()

            AsyncEntityProcessorMonitor(processors, { lags += it }).run()

            lags.map { it.name } shouldBe listOf("a", "b")
        }
    }
})
