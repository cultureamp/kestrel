package com.cultureamp.eventsourcing

import com.cultureamp.eventsourcing.example.ThingCreated
import com.cultureamp.eventsourcing.example.Tweaked
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.joda.time.DateTime
import java.util.*

class BlockingSyncEventProcessorTest : StringSpec({

    "processes events for single processor and updates bookmark" {
        val processedEvents = mutableListOf<Event<EventMetadata>>()
        val bookmarkStore = InMemoryBookmarkStore()
        val processor = EventProcessor.from<ThingCreated> { event, aggregateId ->
            processedEvents.add(Event(UUID.randomUUID(), aggregateId, 1, "test", DateTime.now(), EventMetadata(), event))
        }

        val bookmarkedProcessor = BookmarkedEventProcessor.from(bookmarkStore, "test-processor", processor)
        val syncProcessor = BlockingSyncEventProcessor(listOf(bookmarkedProcessor), timeoutMs = 1000)

        val event1 = createSimpleEvent(ThingCreated, 1)
        val event2 = createSimpleEvent(ThingCreated, 2)
        val events = listOf(event1, event2)

        val result = syncProcessor.processEvents(events)

        result.shouldBeInstanceOf<Right<Unit>>()
        processedEvents.size shouldBe 2
        bookmarkStore.bookmarkFor("test-processor").sequence shouldBe 2
    }

    "processes events for multiple processors in parallel" {
        val processedEventsCreated = mutableListOf<String>()
        val processedEventsTweaked = mutableListOf<String>()
        val bookmarkStore = InMemoryBookmarkStore()

        val processor1 = EventProcessor.from<ThingCreated> { event, aggregateId ->
            Thread.sleep(10) // Simulate work
            processedEventsCreated.add("created-$aggregateId")
        }
        val processor2 = EventProcessor.from<Tweaked> { event, aggregateId ->
            Thread.sleep(10) // Simulate work
            processedEventsTweaked.add("tweaked-$aggregateId-${event.tweak}")
        }

        val bookmarkedProcessor1 = BookmarkedEventProcessor.from(bookmarkStore, "processor-1", processor1)
        val bookmarkedProcessor2 = BookmarkedEventProcessor.from(bookmarkStore, "processor-2", processor2)
        val syncProcessor = BlockingSyncEventProcessor(listOf(bookmarkedProcessor1, bookmarkedProcessor2), timeoutMs = 5000)

        val aggregateId = UUID.randomUUID()
        val event1 = createSimpleEvent(ThingCreated, 1, aggregateId)
        val event2 = createSimpleEvent(Tweaked("updated"), 2, aggregateId)
        val events = listOf(event1, event2)

        val result = syncProcessor.processEvents(events)

        result.shouldBeInstanceOf<Right<Unit>>()
        processedEventsCreated.size shouldBe 1
        processedEventsTweaked.size shouldBe 1
        bookmarkStore.bookmarkFor("processor-1").sequence shouldBe 1
        bookmarkStore.bookmarkFor("processor-2").sequence shouldBe 2
    }

    "skips events already processed based on bookmark" {
        val processedEvents = mutableListOf<String>()
        val bookmarkStore = InMemoryBookmarkStore()
        bookmarkStore.save(Bookmark("test-processor", 1)) // Already processed sequence 1

        val processor = EventProcessor.from<ThingCreated> { event, aggregateId ->
            processedEvents.add("created-$aggregateId")
        }

        val bookmarkedProcessor = BookmarkedEventProcessor.from(bookmarkStore, "test-processor", processor)
        val syncProcessor = BlockingSyncEventProcessor(listOf(bookmarkedProcessor), timeoutMs = 1000)

        val event1 = createSimpleEvent(ThingCreated, 1) // Should be skipped
        val event2 = createSimpleEvent(ThingCreated, 2) // Should be processed
        val events = listOf(event1, event2)

        val result = syncProcessor.processEvents(events)

        result.shouldBeInstanceOf<Right<Unit>>()
        processedEvents.size shouldBe 1 // Only event2 processed
        bookmarkStore.bookmarkFor("test-processor").sequence shouldBe 2
    }

    "filters events by processor domain event classes" {
        val processedEvents = mutableListOf<String>()
        val bookmarkStore = InMemoryBookmarkStore()

        // Processor only handles ThingCreated events
        val processor = EventProcessor.from<ThingCreated> { event, aggregateId ->
            processedEvents.add("created-$aggregateId")
        }

        val bookmarkedProcessor = BookmarkedEventProcessor.from(bookmarkStore, "test-processor", processor)
        val syncProcessor = BlockingSyncEventProcessor(listOf(bookmarkedProcessor), timeoutMs = 1000)

        val aggregateId = UUID.randomUUID()
        val event1 = createSimpleEvent(ThingCreated, 1, aggregateId) // Should be processed
        val event2 = createSimpleEvent(Tweaked("updated"), 2, aggregateId) // Should be ignored
        val events = listOf(event1, event2)

        val result = syncProcessor.processEvents(events)

        result.shouldBeInstanceOf<Right<Unit>>()
        processedEvents.size shouldBe 1 // Only ThingCreated processed
        bookmarkStore.bookmarkFor("test-processor").sequence shouldBe 1 // Only up to ThingCreated
    }

    "handles empty event list gracefully" {
        val bookmarkStore = InMemoryBookmarkStore()
        val processor = EventProcessor.from<ThingCreated> { _, _ -> }
        val bookmarkedProcessor = BookmarkedEventProcessor.from(bookmarkStore, "test-processor", processor)
        val syncProcessor = BlockingSyncEventProcessor(listOf(bookmarkedProcessor), timeoutMs = 1000)

        val result = syncProcessor.processEvents(emptyList())

        result.shouldBeInstanceOf<Right<Unit>>()
        bookmarkStore.bookmarkFor("test-processor").sequence shouldBe 0
    }

    "returns exception error when bookmark store fails" {
        val bookmarkStore = FailingBookmarkStore()
        val processor = EventProcessor.from<ThingCreated> { _, _ ->
            // Do normal work
        }

        val bookmarkedProcessor = BookmarkedEventProcessor.from(bookmarkStore, "failing-processor", processor)
        val syncProcessor = BlockingSyncEventProcessor(listOf(bookmarkedProcessor), timeoutMs = 1000)

        val event = createSimpleEvent(ThingCreated, 1)
        val result = syncProcessor.processEvents(listOf(event))

        result.shouldBeInstanceOf<Left<SyncProcessorException>>()
        val error = (result as Left<SyncProcessorException>).error
        error.processor shouldBe "failing-processor"
        error.cause.message shouldBe "Bookmark store failure"
    }

    "returns exception error when processor throws" {
        val bookmarkStore = InMemoryBookmarkStore()
        val processor = EventProcessor.from<ThingCreated> { _, _ ->
            throw RuntimeException("Processing failed")
        }

        val bookmarkedProcessor = BookmarkedEventProcessor.from(bookmarkStore, "failing-processor", processor)
        val syncProcessor = BlockingSyncEventProcessor(listOf(bookmarkedProcessor), timeoutMs = 1000)

        val event = createSimpleEvent(ThingCreated, 1)
        val result = syncProcessor.processEvents(listOf(event))

        result.shouldBeInstanceOf<Left<SyncProcessorException>>()
        val error = (result as Left<SyncProcessorException>).error
        error.processor shouldBe "failing-processor"
        error.cause.message shouldBe "Processing failed"
    }

    "processes events with no domain event classes filter (handles all events)" {
        val processedEvents = mutableListOf<Pair<DomainEvent, Long>>()
        val bookmarkStore = InMemoryBookmarkStore()

        // Processor that handles all events (empty domainEventClasses)
        val processor = object : EventProcessor<EventMetadata> {
            override fun process(event: Event<out EventMetadata>, sequence: Long) {
                processedEvents.add(event.domainEvent to sequence)
            }
            override fun domainEventClasses() = emptyList<kotlin.reflect.KClass<out DomainEvent>>()
        }

        val bookmarkedProcessor = BookmarkedEventProcessor.from(bookmarkStore, "catch-all-processor", processor)
        val syncProcessor = BlockingSyncEventProcessor(listOf(bookmarkedProcessor), timeoutMs = 1000)

        val aggregateId = UUID.randomUUID()
        val event1 = createSimpleEvent(ThingCreated, 1, aggregateId)
        val event2 = createSimpleEvent(Tweaked("updated"), 2, aggregateId)
        val events = listOf(event1, event2)

        val result = syncProcessor.processEvents(events)

        result.shouldBeInstanceOf<Right<Unit>>()
        processedEvents.size shouldBe 2 // Both events processed
        bookmarkStore.bookmarkFor("catch-all-processor").sequence shouldBe 2
    }

    "processes events successfully when validation passes" {
        val processedEvents = mutableListOf<Event<EventMetadata>>()
        val bookmarkStore = InMemoryBookmarkStore()
        val eventsSequenceStats = MockEventsSequenceStats(100L) // Current max sequence

        val validator = SyncProcessorCatchupValidator<EventMetadata>(
            eventsSequenceStats,
            CatchupValidationConfig(validationMode = CatchupValidationMode.ENFORCE)
        )

        val processor = EventProcessor.from<ThingCreated> { event, aggregateId ->
            processedEvents.add(Event(UUID.randomUUID(), aggregateId, 1, "test", DateTime.now(), EventMetadata(), event))
        }

        val bookmarkedProcessor = BookmarkedEventProcessor.from(bookmarkStore, "test-processor", processor)
        bookmarkStore.save(Bookmark("test-processor", 100L)) // Up to date

        val syncProcessor = BlockingSyncEventProcessor(
            eventProcessors = listOf(bookmarkedProcessor),
            timeoutMs = 1000,
            catchupValidator = validator
        )

        val event1 = createSimpleEvent(ThingCreated, 101)
        val events = listOf(event1)

        val result = syncProcessor.processEvents(events)

        result.shouldBeInstanceOf<Right<Unit>>()
        processedEvents.size shouldBe 1
        bookmarkStore.bookmarkFor("test-processor").sequence shouldBe 101
    }

    "fails validation when processor is behind in ENFORCE mode" {
        val bookmarkStore = InMemoryBookmarkStore()
        val eventsSequenceStats = MockEventsSequenceStats(100L)

        val validator = SyncProcessorCatchupValidator<EventMetadata>(
            eventsSequenceStats,
            CatchupValidationConfig(
                validationMode = CatchupValidationMode.ENFORCE,
                allowableGap = 5
            )
        )

        val processor = EventProcessor.from<ThingCreated> { _, _ -> }
        val bookmarkedProcessor = BookmarkedEventProcessor.from(bookmarkStore, "test-processor", processor)
        bookmarkStore.save(Bookmark("test-processor", 90L)) // Behind by 10, gap tolerance is 5

        val syncProcessor = BlockingSyncEventProcessor(
            eventProcessors = listOf(bookmarkedProcessor),
            timeoutMs = 1000,
            catchupValidator = validator
        )

        val event1 = createSimpleEvent(ThingCreated, 101)
        val events = listOf(event1)

        val result = syncProcessor.processEvents(events)

        result.shouldBeInstanceOf<Left<SyncProcessorCatchupValidationError>>()
        val error = (result as Left<SyncProcessorCatchupValidationError>).error
        error.processor shouldBe "Multiple processors"
    }

    "processes events with warning when processor is behind in WARN mode" {
        val processedEvents = mutableListOf<Event<EventMetadata>>()
        val bookmarkStore = InMemoryBookmarkStore()
        val eventsSequenceStats = MockEventsSequenceStats(100L)

        val validator = SyncProcessorCatchupValidator<EventMetadata>(
            eventsSequenceStats,
            CatchupValidationConfig(validationMode = CatchupValidationMode.WARN)
        )

        val processor = EventProcessor.from<ThingCreated> { event, aggregateId ->
            processedEvents.add(Event(UUID.randomUUID(), aggregateId, 1, "test", DateTime.now(), EventMetadata(), event))
        }

        val bookmarkedProcessor = BookmarkedEventProcessor.from(bookmarkStore, "test-processor", processor)
        bookmarkStore.save(Bookmark("test-processor", 90L)) // Behind by 10

        val syncProcessor = BlockingSyncEventProcessor(
            eventProcessors = listOf(bookmarkedProcessor),
            timeoutMs = 1000,
            catchupValidator = validator
        )

        val event1 = createSimpleEvent(ThingCreated, 101)
        val events = listOf(event1)

        val result = syncProcessor.processEvents(events)

        result.shouldBeInstanceOf<Right<Unit>>() // Should succeed with warning
        processedEvents.size shouldBe 1
    }

    "processes events without validation when validator is null" {
        val processedEvents = mutableListOf<Event<EventMetadata>>()
        val bookmarkStore = InMemoryBookmarkStore()

        val processor = EventProcessor.from<ThingCreated> { event, aggregateId ->
            processedEvents.add(Event(UUID.randomUUID(), aggregateId, 1, "test", DateTime.now(), EventMetadata(), event))
        }

        val bookmarkedProcessor = BookmarkedEventProcessor.from(bookmarkStore, "test-processor", processor)

        val syncProcessor = BlockingSyncEventProcessor(
            eventProcessors = listOf(bookmarkedProcessor),
            timeoutMs = 1000,
            catchupValidator = null // No validation
        )

        val event1 = createSimpleEvent(ThingCreated, 1)
        val events = listOf(event1)

        val result = syncProcessor.processEvents(events)

        result.shouldBeInstanceOf<Right<Unit>>()
        processedEvents.size shouldBe 1
    }

    "applies per-processor validation configs" {
        val processedEvents = mutableListOf<Event<EventMetadata>>()
        val bookmarkStore = InMemoryBookmarkStore()
        val eventsSequenceStats = MockEventsSequenceStats(100L)

        val validator = SyncProcessorCatchupValidator<EventMetadata>(
            eventsSequenceStats,
            CatchupValidationConfig(validationMode = CatchupValidationMode.ENFORCE)
        )

        val processor1 = EventProcessor.from<ThingCreated> { event, aggregateId ->
            processedEvents.add(Event(UUID.randomUUID(), aggregateId, 1, "test", DateTime.now(), EventMetadata(), event))
        }
        val processor2 = EventProcessor.from<Tweaked> { event, aggregateId ->
            processedEvents.add(Event(UUID.randomUUID(), aggregateId, 1, "test", DateTime.now(), EventMetadata(), event))
        }

        val bookmarkedProcessor1 = BookmarkedEventProcessor.from(bookmarkStore, "processor1", processor1)
        val bookmarkedProcessor2 = BookmarkedEventProcessor.from(bookmarkStore, "processor2", processor2)

        bookmarkStore.save(Bookmark("processor1", 95L)) // Behind by 5
        bookmarkStore.save(Bookmark("processor2", 85L)) // Behind by 15

        val validationConfigs = mapOf(
            "processor1" to CatchupValidationConfig(allowableGap = 10), // Should pass
            "processor2" to CatchupValidationConfig(validationMode = CatchupValidationMode.SKIP) // Should be skipped
        )

        val syncProcessor = BlockingSyncEventProcessor(
            eventProcessors = listOf(bookmarkedProcessor1, bookmarkedProcessor2),
            timeoutMs = 1000,
            catchupValidator = validator,
            validationConfigs = validationConfigs
        )

        val event1 = createSimpleEvent(ThingCreated, 101)
        val event2 = createSimpleEvent(Tweaked("updated"), 102)
        val events = listOf(event1, event2)

        val result = syncProcessor.processEvents(events)

        result.shouldBeInstanceOf<Right<Unit>>()
        processedEvents.size shouldBe 2
    }
})

private fun createSimpleEvent(domainEvent: DomainEvent, sequence: Long, aggregateId: UUID = UUID.randomUUID()): SequencedEvent<EventMetadata> {
    return SequencedEvent(
        Event(
            id = UUID.randomUUID(),
            aggregateId = aggregateId,
            aggregateSequence = sequence,
            aggregateType = "thing",
            createdAt = DateTime.now(),
            metadata = EventMetadata(),
            domainEvent = domainEvent
        ),
        sequence
    )
}

class InMemoryBookmarkStore : BookmarkStore {
    private val bookmarks = mutableMapOf<String, Bookmark>()

    override fun bookmarkFor(bookmarkName: String): Bookmark =
        bookmarks[bookmarkName] ?: Bookmark(bookmarkName, 0)

    override fun bookmarksFor(bookmarkNames: Set<String>): Set<Bookmark> =
        bookmarkNames.map { bookmarkFor(it) }.toSet()

    override fun save(bookmark: Bookmark) {
        bookmarks[bookmark.name] = bookmark
    }

    override fun checkoutBookmark(bookmarkName: String): Either<LockNotObtained, Bookmark> =
        Right(bookmarkFor(bookmarkName))
}

class FailingBookmarkStore : BookmarkStore {
    override fun bookmarkFor(bookmarkName: String): Bookmark =
        Bookmark(bookmarkName, 0)

    override fun bookmarksFor(bookmarkNames: Set<String>): Set<Bookmark> =
        bookmarkNames.map { bookmarkFor(it) }.toSet()

    override fun save(bookmark: Bookmark) {
        throw RuntimeException("Bookmark store failure")
    }

    override fun checkoutBookmark(bookmarkName: String): Either<LockNotObtained, Bookmark> =
        Right(bookmarkFor(bookmarkName))
}

class MockEventsSequenceStats(private val maxSequence: Long) : EventsSequenceStats {
    override fun lastSequence(eventClasses: List<kotlin.reflect.KClass<out DomainEvent>>): Long {
        return maxSequence
    }

    override fun save(eventClass: kotlin.reflect.KClass<out DomainEvent>, sequence: Long) {
        // Mock implementation - no-op
    }
}