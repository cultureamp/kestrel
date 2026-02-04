package com.cultureamp.eventsourcing

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.joda.time.DateTime
import java.util.*

class BlockingSyncEventProcessorUpdaterTest : StringSpec({

    "should filter events by processor domain event classes" {
        val processedEvents = mutableListOf<String>()

        // Processor that only handles FooEvent events
        val fooProcessor = BookmarkedEventProcessor.from(
            testBookmarkStore(),
            "foo-processor",
            EventProcessor.from<FooEvent, SpecificMetadata> { event, _, _, _ ->
                processedEvents.add("foo: ${event.foo}")
            }
        )

        // Processor that only handles BarEvent events
        val barProcessor = BookmarkedEventProcessor.from(
            testBookmarkStore(),
            "bar-processor",
            EventProcessor.from<BarEvent, SpecificMetadata> { event, _, _, _ ->
                processedEvents.add("bar: ${event.bar}")
            }
        )

        val updater = BlockingSyncEventProcessorUpdater<SpecificMetadata>(
            listOf(fooProcessor, barProcessor)
        )

        val events = listOf(
            createSequencedEvent(FooEvent("test-foo"), 1L),
            createSequencedEvent(BarEvent("test-bar"), 2L),
            createSequencedEvent(BazEvent("test-baz"), 3L) // Not handled by either processor
        )

        updater.processEvents(events)

        processedEvents shouldBe listOf(
            "foo: test-foo",
            "bar: test-bar"
        )
    }

    "should handle multiple processors with same event type" {
        val processedEvents = mutableListOf<String>()

        // Two processors that both handle FooEvent events
        val processor1 = BookmarkedEventProcessor.from(
            testBookmarkStore(),
            "processor1",
            EventProcessor.from<FooEvent, SpecificMetadata> { event, _, _, _ ->
                processedEvents.add("processor1: ${event.foo}")
            }
        )

        val processor2 = BookmarkedEventProcessor.from(
            testBookmarkStore(),
            "processor2",
            EventProcessor.from<FooEvent, SpecificMetadata> { event, _, _, _ ->
                processedEvents.add("processor2: ${event.foo}")
            }
        )

        val updater = BlockingSyncEventProcessorUpdater<SpecificMetadata>(
            listOf(processor1, processor2)
        )

        val events = listOf(
            createSequencedEvent(FooEvent("test-foo"), 1L)
        )

        updater.processEvents(events)

        processedEvents shouldBe listOf(
            "processor1: test-foo",
            "processor2: test-foo"
        )
    }

    "should handle empty event list" {
        val processedEvents = mutableListOf<String>()

        val processor = BookmarkedEventProcessor.from(
            testBookmarkStore(),
            "test-processor",
            EventProcessor.from<FooEvent, SpecificMetadata> { event, _, _, _ ->
                processedEvents.add("processed: ${event.foo}")
            }
        )

        val updater = BlockingSyncEventProcessorUpdater<SpecificMetadata>(listOf(processor))

        updater.processEvents(emptyList())

        processedEvents shouldBe emptyList()
    }

    "should handle empty processor list" {
        val updater = BlockingSyncEventProcessorUpdater<SpecificMetadata>(emptyList())

        val events = listOf(
            createSequencedEvent(FooEvent("test-foo"), 1L)
        )

        // Should not throw any exception
        updater.processEvents(events)
    }
})

private fun testBookmarkStore() = object : BookmarkStore {
    override fun bookmarkFor(bookmarkName: String): Bookmark = Bookmark(bookmarkName, 0L)
    override fun bookmarksFor(bookmarkNames: Set<String>) = bookmarkNames.map { bookmarkFor(it) }.toSet()
    override fun save(bookmark: Bookmark) = Unit
    override fun checkoutBookmark(bookmarkName: String): Either<LockNotObtained, Bookmark> = Right(bookmarkFor(bookmarkName))
}

private fun createSequencedEvent(domainEvent: DomainEvent, sequence: Long): SequencedEvent<SpecificMetadata> {
    val event = Event(
        id = UUID.randomUUID(),
        aggregateId = UUID.randomUUID(),
        aggregateSequence = 1L,
        aggregateType = "TestAggregate",
        createdAt = DateTime.now(),
        metadata = SpecificMetadata("test-field"),
        domainEvent = domainEvent
    )
    return SequencedEvent(event, sequence)
}