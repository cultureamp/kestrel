package com.cultureamp.eventsourcing

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.joda.time.DateTime
import java.util.*
import kotlin.reflect.KClass

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
            listOf(fooProcessor, barProcessor),
            testEventSource()
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
            listOf(processor1, processor2),
            testEventSource()
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

        val updater = BlockingSyncEventProcessorUpdater<SpecificMetadata>(
            listOf(processor),
            testEventSource()
        )

        updater.processEvents(emptyList())

        processedEvents shouldBe emptyList()
    }

    "should handle empty processor list" {
        val updater = BlockingSyncEventProcessorUpdater<SpecificMetadata>(
            emptyList(),
            testEventSource()
        )

        val events = listOf(
            createSequencedEvent(FooEvent("test-foo"), 1L)
        )

        // Should not throw any exception
        updater.processEvents(events)
    }

    "should catch up processor when behind current events" {
        val processedEvents = mutableListOf<String>()
        val missedEvents = mutableListOf<SequencedEvent<SpecificMetadata>>()

        // Create missed events that should be fetched during catch-up
        missedEvents.add(createSequencedEvent(FooEvent("missed-foo-1"), 5L))
        missedEvents.add(createSequencedEvent(FooEvent("missed-foo-2"), 6L))

        val testEventSource = object : EventSource<SpecificMetadata> {
            override fun getAfter(sequence: Long, eventClasses: List<KClass<out DomainEvent>>, batchSize: Int): List<SequencedEvent<SpecificMetadata>> {
                return when (sequence) {
                    3L -> missedEvents.filter { it.sequence > sequence && it.sequence < 10L }
                    else -> emptyList()
                }
            }
        }

        // BookmarkStore that returns bookmark at sequence 3, so there's a gap before new events at sequence 10
        val bookmarkStore = object : BookmarkStore {
            override fun bookmarkFor(bookmarkName: String): Bookmark = Bookmark(bookmarkName, 3L)
            override fun bookmarksFor(bookmarkNames: Set<String>) = bookmarkNames.map { bookmarkFor(it) }.toSet()
            override fun save(bookmark: Bookmark) = Unit
            override fun checkoutBookmark(bookmarkName: String): Either<LockNotObtained, Bookmark> = Right(bookmarkFor(bookmarkName))
        }

        val processor = BookmarkedEventProcessor.from(
            bookmarkStore,
            "test-processor",
            EventProcessor.from<FooEvent, SpecificMetadata> { event, _, _, _ ->
                processedEvents.add("processed: ${event.foo}")
            }
        )

        val updater = BlockingSyncEventProcessorUpdater<SpecificMetadata>(
            listOf(processor),
            testEventSource
        )

        // New events starting at sequence 10 (gap from bookmark at 3)
        val newEvents = listOf(
            createSequencedEvent(FooEvent("new-foo"), 10L)
        )

        updater.processEvents(newEvents)

        // Should process missed events first, then new events
        processedEvents shouldBe listOf(
            "processed: missed-foo-1",
            "processed: missed-foo-2",
            "processed: new-foo"
        )
    }

    "should not fetch missed events when processor is caught up" {
        val processedEvents = mutableListOf<String>()
        var getAfterCalled = false

        val testEventSource = object : EventSource<SpecificMetadata> {
            override fun getAfter(sequence: Long, eventClasses: List<KClass<out DomainEvent>>, batchSize: Int): List<SequencedEvent<SpecificMetadata>> {
                getAfterCalled = true
                return emptyList()
            }
        }

        // BookmarkStore that returns bookmark at sequence 9, so no gap with new events at sequence 10
        val bookmarkStore = object : BookmarkStore {
            override fun bookmarkFor(bookmarkName: String): Bookmark = Bookmark(bookmarkName, 9L)
            override fun bookmarksFor(bookmarkNames: Set<String>) = bookmarkNames.map { bookmarkFor(it) }.toSet()
            override fun save(bookmark: Bookmark) = Unit
            override fun checkoutBookmark(bookmarkName: String): Either<LockNotObtained, Bookmark> = Right(bookmarkFor(bookmarkName))
        }

        val processor = BookmarkedEventProcessor.from(
            bookmarkStore,
            "test-processor",
            EventProcessor.from<FooEvent, SpecificMetadata> { event, _, _, _ ->
                processedEvents.add("processed: ${event.foo}")
            }
        )

        val updater = BlockingSyncEventProcessorUpdater<SpecificMetadata>(
            listOf(processor),
            testEventSource
        )

        val newEvents = listOf(
            createSequencedEvent(FooEvent("new-foo"), 10L)
        )

        updater.processEvents(newEvents)

        // Should only process new events
        processedEvents shouldBe listOf("processed: new-foo")
        // getAfter should not have been called since processor is caught up
        getAfterCalled shouldBe false
    }
})

private fun testBookmarkStore() = object : BookmarkStore {
    override fun bookmarkFor(bookmarkName: String): Bookmark = Bookmark(bookmarkName, 0L)
    override fun bookmarksFor(bookmarkNames: Set<String>) = bookmarkNames.map { bookmarkFor(it) }.toSet()
    override fun save(bookmark: Bookmark) = Unit
    override fun checkoutBookmark(bookmarkName: String): Either<LockNotObtained, Bookmark> = Right(bookmarkFor(bookmarkName))
}

private fun testEventSource() = object : EventSource<SpecificMetadata> {
    override fun getAfter(sequence: Long, eventClasses: List<KClass<out DomainEvent>>, batchSize: Int): List<SequencedEvent<SpecificMetadata>> {
        return emptyList() // Simple implementation for basic tests
    }
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