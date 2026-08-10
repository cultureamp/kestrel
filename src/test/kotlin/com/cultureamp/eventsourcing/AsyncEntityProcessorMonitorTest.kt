package com.cultureamp.eventsourcing

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import org.joda.time.DateTime
import java.util.UUID

class AsyncEntityProcessorMonitorTest : DescribeSpec({
    val baseTime = DateTime(2026, 8, 10, 9, 0, 0, 0)

    fun widget(name: String, secondsAfterBase: Int) = Widget(UUID.randomUUID(), name, baseTime.plusSeconds(secondsAfterBase))

    fun monitoredProcessor(widgets: List<Widget>, bookmarkStore: EntityBookmarkStore, batchSize: Int = 1000): BatchedAsyncEntityProcessor<Widget> {
        val source = InMemoryEntitySource(widgets.map { PositionedEntity(it, it.position) })
        return BatchedAsyncEntityProcessor(
            entitySource = source,
            entityUpdatedAtStats = source,
            bookmarkStore = bookmarkStore,
            bookmarkName = "widgets",
            entityProcessor = EntityProcessor.from { _: Widget -> },
            batchSize = batchSize,
            clock = { baseTime.plusHours(1) },
            startLog = {},
            endLog = { _, _ -> },
        )
    }

    describe("run") {
        it("reports full lag in milliseconds and that nothing has been processed yet") {
            val widgets = listOf(widget("first", 1), widget("last", 61))
            val processor = monitoredProcessor(widgets, InMemoryEntityBookmarkStore())
            var lag: EntityLag? = null
            val monitor = AsyncEntityProcessorMonitor(listOf(processor), { lag = it }, clock = { baseTime.plusSeconds(120) })

            monitor.run()

            lag!!.name shouldBe "widgets"
            lag!!.hasStarted shouldBe false
            lag!!.lastUpdatedAt?.millis shouldBe baseTime.plusSeconds(61).millis
            lag!!.lagMs shouldBe null
            lag!!.latencyMs shouldBe null
        }

        it("reports how far behind the head of the table a partially caught-up processor is") {
            val widgets = listOf(widget("first", 1), widget("last", 61))
            val processor = monitoredProcessor(widgets, InMemoryEntityBookmarkStore(), batchSize = 1)
            var lag: EntityLag? = null
            val monitor = AsyncEntityProcessorMonitor(listOf(processor), { lag = it }, clock = { baseTime.plusSeconds(120) })

            processor.processOneBatch()
            monitor.run()

            lag!!.hasStarted shouldBe true
            lag!!.bookmarkUpdatedAt?.millis shouldBe baseTime.plusSeconds(1).millis
            lag!!.lagMs shouldBe 60_000
            lag!!.latencyMs shouldBe 119_000
        }

        it("reports zero lag once caught up") {
            val widgets = listOf(widget("first", 1), widget("last", 61))
            val processor = monitoredProcessor(widgets, InMemoryEntityBookmarkStore())
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
                    entitySource = InMemoryEntitySource(emptyList<PositionedEntity<Widget>>()),
                    entityUpdatedAtStats = EntityUpdatedAtStats.from { baseTime },
                    bookmarkStore = InMemoryEntityBookmarkStore(),
                    bookmarkName = name,
                    entityProcessor = EntityProcessor.from { _: Widget -> },
                    startLog = {},
                    endLog = { _, _ -> },
                )
            }
            val lags = mutableListOf<EntityLag>()

            AsyncEntityProcessorMonitor(processors, { lags += it }).run()

            lags.map { it.name } shouldBe listOf("a", "b")
        }
    }

    describe("BlockingAsyncEntityProcessorWaiter") {
        it("returns immediately once every processor's bookmark has reached the position") {
            val target = widget("target", 1)
            val bookmarkStore = InMemoryEntityBookmarkStore()
            val processor = monitoredProcessor(listOf(target), bookmarkStore)
            processor.processOneBatch()

            BlockingAsyncEntityProcessorWaiter(listOf(processor), maxWaitMs = 100).waitUntilProcessed(target.position)
        }
    }
})
