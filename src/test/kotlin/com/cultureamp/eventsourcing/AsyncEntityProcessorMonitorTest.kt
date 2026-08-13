package com.cultureamp.eventsourcing

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import java.time.Duration
import java.time.Instant
import java.util.UUID

class AsyncEntityProcessorMonitorTest : DescribeSpec({
    val baseTime = Instant.parse("2026-08-10T09:00:00Z")
    val accountId = UUID.randomUUID()

    fun goalRelationship(secondsAfterBase: Int) =
        GoalRelationship(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), accountId, createdAt = fixtureCreatedAt, updatedAt = baseTime.plusSeconds(secondsAfterBase.toLong()))

    fun monitoredProcessor(
        goalRelationships: List<GoalRelationship>,
        bookmarkStore: EntityBookmarkStore,
        batchSize: Int = 1000,
    ): BatchedAsyncEntityProcessor<GoalRelationship> {
        val source = InMemoryEntitySource(goalRelationships.map { PositionedEntity(it, it.position) })
        return BatchedAsyncEntityProcessor(
            entitySource = source,
            entityUpdatedAtStats = source,
            bookmarkStore = bookmarkStore,
            bookmarkName = "goal-relationships",
            entityProcessor = EntityProcessor.from { _: GoalRelationship -> },
            safeBoundary = SafeBoundary { baseTime.plus(Duration.ofHours(1)) },
            batchSize = batchSize,
            startLog = {},
            endLog = { _, _ -> },
        )
    }

    describe("run") {
        it("reports that nothing has been processed yet") {
            val goalRelationships = listOf(goalRelationship(1), goalRelationship(61))
            val processor = monitoredProcessor(goalRelationships, InMemoryEntityBookmarkStore())
            var lag: EntityLag? = null
            val monitor = AsyncEntityProcessorMonitor(listOf(processor), { lag = it }, clock = { baseTime.plusSeconds(120) })

            monitor.run()

            lag!!.name shouldBe "goal-relationships"
            lag!!.hasStarted shouldBe false
            lag!!.lastUpdatedAt shouldBe baseTime.plusSeconds(61)
            lag!!.lagMs shouldBe null
            lag!!.latencyMs shouldBe null
        }

        it("reports how far behind the head of the table a partially caught-up processor is") {
            val goalRelationships = listOf(goalRelationship(1), goalRelationship(61))
            val processor = monitoredProcessor(goalRelationships, InMemoryEntityBookmarkStore(), batchSize = 1)
            var lag: EntityLag? = null
            val monitor = AsyncEntityProcessorMonitor(listOf(processor), { lag = it }, clock = { baseTime.plusSeconds(120) })

            processor.processOneBatch()
            monitor.run()

            lag!!.hasStarted shouldBe true
            lag!!.bookmarkUpdatedAt shouldBe baseTime.plusSeconds(1)
            lag!!.lagMs shouldBe 60_000
            lag!!.latencyMs shouldBe 119_000
        }

        it("reports zero lag once caught up") {
            val goalRelationships = listOf(goalRelationship(1), goalRelationship(61))
            val processor = monitoredProcessor(goalRelationships, InMemoryEntityBookmarkStore())
            var lag: EntityLag? = null
            val monitor = AsyncEntityProcessorMonitor(listOf(processor), { lag = it }, clock = { baseTime.plusSeconds(120) })

            processor.processOneBatch()
            monitor.run()

            lag!!.lagMs shouldBe 0
            lag!!.latencyMs shouldBe 59_000
        }

        it("reports null lag for an empty table") {
            val processor = monitoredProcessor(emptyList(), InMemoryEntityBookmarkStore())
            var lag: EntityLag? = null
            val monitor = AsyncEntityProcessorMonitor(listOf(processor), { lag = it }, clock = { baseTime })

            monitor.run()

            lag!!.lastUpdatedAt shouldBe null
            lag!!.lagMs shouldBe null
        }

        it("reports on every processor it is given") {
            val processors = listOf("a", "b").map { name ->
                BatchedAsyncEntityProcessor(
                    entitySource = InMemoryEntitySource(emptyList<PositionedEntity<GoalRelationship>>()),
                    entityUpdatedAtStats = EntityUpdatedAtStats.from { baseTime },
                    bookmarkStore = InMemoryEntityBookmarkStore(),
                    bookmarkName = name,
                    entityProcessor = EntityProcessor.from { _: GoalRelationship -> },
                    safeBoundary = SafeBoundary { baseTime.plus(Duration.ofHours(1)) },
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
