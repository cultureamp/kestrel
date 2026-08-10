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

    fun widget(name: String, secondsAfterBase: Int, id: UUID = UUID.randomUUID()) =
        Widget(id, name, baseTime.plusSeconds(secondsAfterBase))

    fun positioned(widget: Widget) = PositionedEntity(widget, widget.position)

    fun processorFor(
        widgets: List<Widget>,
        processed: MutableList<Widget>,
        bookmarkStore: EntityBookmarkStore,
        batchSize: Int = 1000,
        timestampDelayMs: Long = 1000,
        clock: () -> DateTime = wellAfterAnyRow,
    ) = BatchedAsyncEntityProcessor(
        entitySource = InMemoryEntitySource(widgets.map(::positioned)),
        entityUpdatedAtStats = InMemoryEntitySource(widgets.map(::positioned)),
        bookmarkStore = bookmarkStore,
        bookmarkName = "widgets",
        entityProcessor = EntityProcessor.from { widget: Widget -> processed += widget },
        batchSize = batchSize,
        timestampDelayMs = timestampDelayMs,
        clock = clock,
        startLog = {},
        endLog = { _, _ -> },
    )

    describe("processOneBatch") {
        it("processes rows in updated-at order and bookmarks each one") {
            val first = widget("first", 1)
            val second = widget("second", 2)
            val third = widget("third", 3)
            val processed = mutableListOf<Widget>()
            val bookmarkStore = InMemoryEntityBookmarkStore()

            // deliberately out of order in the source, which is expected to sort them
            val action = processorFor(listOf(third, first, second), processed, bookmarkStore).processOneBatch()

            processed.map { it.name } shouldBe listOf("first", "second", "third")
            bookmarkStore.saved.map { it.position } shouldBe listOf(first.position, second.position, third.position)
            bookmarkStore.bookmarkFor("widgets") shouldBe EntityBookmark("widgets", third.position)
            action shouldBe Action.Wait
        }

        it("starts from an existing bookmark rather than the beginning") {
            val first = widget("first", 1)
            val second = widget("second", 2)
            val processed = mutableListOf<Widget>()
            val bookmarkStore = InMemoryEntityBookmarkStore()
            bookmarkStore.save(EntityBookmark("widgets", first.position))

            processorFor(listOf(first, second), processed, bookmarkStore).processOneBatch()

            processed.map { it.name } shouldBe listOf("second")
        }

        it("tiebreaks on id so that rows sharing an updated-at are each processed exactly once across batches") {
            val sameSecond = (1..5).map { widget("widget-$it", 1) }
            val processed = mutableListOf<Widget>()
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
            val old = widget("old", 0)
            val fresh = widget("fresh", 10)
            val processed = mutableListOf<Widget>()
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
            val widgets = (1..3).map { widget("widget-$it", it) }
            val processed = mutableListOf<Widget>()
            val processor = processorFor(widgets, processed, InMemoryEntityBookmarkStore(), batchSize = 3)

            processor.processOneBatch() shouldBe Action.Continue
        }

        it("waits without processing anything when the bookmark lock can't be obtained") {
            val processed = mutableListOf<Widget>()
            val bookmarkStore = InMemoryEntityBookmarkStore(lockObtainable = false)

            val action = processorFor(listOf(widget("first", 1)), processed, bookmarkStore).processOneBatch()

            action shouldBe Action.Wait
            processed shouldBe emptyList()
            bookmarkStore.saved shouldBe emptyList()
        }

        it("fails loudly when the source re-returns the row the bookmark is already at") {
            val stuck = widget("stuck", 1)
            val bookmarkStore = InMemoryEntityBookmarkStore()
            bookmarkStore.save(EntityBookmark("widgets", stuck.position))
            val processor = BatchedAsyncEntityProcessor(
                entitySource = EntitySource.from { _, _, _ -> listOf(PositionedEntity(stuck, stuck.position)) },
                entityUpdatedAtStats = EntityUpdatedAtStats.from { stuck.updatedAt },
                bookmarkStore = bookmarkStore,
                bookmarkName = "widgets",
                entityProcessor = EntityProcessor.from { _: Widget -> },
                clock = wellAfterAnyRow,
                startLog = {},
                endLog = { _, _ -> },
            )

            shouldThrow<EntitySourceStalledException> { processor.processOneBatch() }
        }

        it("fails loudly when the source returns rows out of order") {
            val earlier = widget("earlier", 1)
            val later = widget("later", 2)
            val processor = BatchedAsyncEntityProcessor(
                entitySource = EntitySource.from { _, _, _ ->
                    listOf(PositionedEntity(later, later.position), PositionedEntity(earlier, earlier.position))
                },
                entityUpdatedAtStats = EntityUpdatedAtStats.from { later.updatedAt },
                bookmarkStore = InMemoryEntityBookmarkStore(),
                bookmarkName = "widgets",
                entityProcessor = EntityProcessor.from { _: Widget -> },
                clock = wellAfterAnyRow,
                startLog = {},
                endLog = { _, _ -> },
            )

            shouldThrow<EntitySourceOrderingException> { processor.processOneBatch() }
        }

        it("reports statistics for each entity processed") {
            val widgets = (1..2).map { widget("widget-$it", it) }
            val collected = mutableListOf<PositionedEntity<*>>()
            val stats = object : EntityStatisticsCollector {
                override fun entityProcessed(processor: AsyncEntityProcessor<*>, entity: PositionedEntity<*>, durationMs: Long) {
                    collected += entity
                }
            }
            BatchedAsyncEntityProcessor(
                entitySource = InMemoryEntitySource(widgets.map(::positioned)),
                entityUpdatedAtStats = InMemoryEntitySource(widgets.map(::positioned)),
                bookmarkStore = InMemoryEntityBookmarkStore(),
                bookmarkName = "widgets",
                entityProcessor = EntityProcessor.from { _: Widget -> },
                clock = wellAfterAnyRow,
                startLog = {},
                endLog = { _, _ -> },
                stats = stats,
            ).processOneBatch()

            collected.map { it.position } shouldBe widgets.map { it.position }
        }
    }

    describe("EntityProcessor.compose") {
        it("shares one bookmark across several processors") {
            val widgets = (1..2).map { widget("widget-$it", it) }
            val first = mutableListOf<String>()
            val second = mutableListOf<EntityPosition>()
            val bookmarkStore = InMemoryEntityBookmarkStore()

            BatchedAsyncEntityProcessor(
                entitySource = InMemoryEntitySource(widgets.map(::positioned)),
                entityUpdatedAtStats = InMemoryEntitySource(widgets.map(::positioned)),
                bookmarkStore = bookmarkStore,
                bookmarkName = "widgets",
                entityProcessor = EntityProcessor.compose(
                    EntityProcessor.from { widget: Widget -> first += widget.name },
                    EntityProcessor.from { _: Widget, position: EntityPosition -> second += position },
                ),
                clock = wellAfterAnyRow,
                startLog = {},
                endLog = { _, _ -> },
            ).processOneBatch()

            first shouldBe listOf("widget-1", "widget-2")
            second shouldBe widgets.map { it.position }
            bookmarkStore.bookmarkFor("widgets").position shouldBe widgets.last().position
        }
    }
})
