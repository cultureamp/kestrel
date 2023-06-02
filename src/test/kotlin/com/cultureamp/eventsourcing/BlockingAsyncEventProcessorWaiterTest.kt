package com.cultureamp.eventsourcing

import io.kotest.assertions.fail
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.TimeoutCancellationException
import org.joda.time.DateTime
import java.util.UUID
import kotlin.reflect.KClass

class BlockingAsyncEventProcessorWaiterTest : DescribeSpec({
    val fooEvent = sequencedEventFor(FooEvent("foo"), 25)
    val barEvent = sequencedEventFor(BarEvent("bar"), 69)
    val bazEvent = sequencedEventFor(BazEvent("baz"), 35)
    val quuxEvent = sequencedEventFor(QuuxEvent("quux"), 71)
    val sequencedEvents = listOf(fooEvent, barEvent, bazEvent, quuxEvent)

    describe("BlockingAsyncEventProcessorWaiter") {
        it("loops until all bookmarks are up to date") {
            val firstBookmarkStore = bookmarkStoreCountingUpFrom(30)
            val secondBookmarkStore = bookmarkStoreCountingUpFrom(70)
            val fooEventProcessor = eventProcessorFor("FooProjector", listOf(FooEvent::class), firstBookmarkStore)
            val barEventProcessor = eventProcessorFor("BarProjector", listOf(BarEvent::class), secondBookmarkStore)
            val bazEventProcessor = eventProcessorFor("BazProjector", listOf(BazEvent::class), firstBookmarkStore)
            val quuxEventProcessor = eventProcessorFor("QuuxProjector", listOf(QuuxEvent::class), secondBookmarkStore)
            val eventProcessors = listOf(fooEventProcessor, barEventProcessor, bazEventProcessor, quuxEventProcessor)

            val captured = mutableListOf<String>()
            val waiter = BlockingAsyncEventProcessorWaiter(eventProcessors, maxWaitMs = 5000L, pollWaitMs = 0L, logger = { captured.add(it) })
            waiter.waitUntilProcessed(sequencedEvents)
            captured shouldBe listOf(
                "Waiting for eventProcessors to catch up. Lagging: {BazProjector=5, QuuxProjector=1}",
                "Waiting for eventProcessors to catch up. Lagging: {BazProjector=4}",
                "Waiting for eventProcessors to catch up. Lagging: {BazProjector=3}",
                "Waiting for eventProcessors to catch up. Lagging: {BazProjector=2}",
                "Waiting for eventProcessors to catch up. Lagging: {BazProjector=1}",
            )
        }

        it("throws an exception when looping exceeds timeout") {
            val bookmarkStore = bookmarkStoreCountingUpFrom(0)
            val eventProcessor = eventProcessorFor("Arbitrary", listOf(FooEvent::class), bookmarkStore)

            val waiter = BlockingAsyncEventProcessorWaiter(listOf(eventProcessor), maxWaitMs = 1L, pollWaitMs = 1000L)
            val exception = shouldThrow<TimeoutCancellationException> {
                waiter.waitUntilProcessed(sequencedEvents)
            }
            exception.message shouldBe "Timed out waiting for 1 ms"
        }
    }
})

val alwaysFailsEventSource = object : EventSource<SpecificMetadata> {
    override fun getAfter(sequence: Long, eventClasses: List<KClass<out DomainEvent>>, batchSize: Int) = fail("Should not be called")
    override fun lastSequence(eventClasses: List<KClass<out DomainEvent>>) = fail("Should not be called")
}

fun bookmarkStoreCountingUpFrom(sequence: Long) = object : BookmarkStore {
    val latest = mutableMapOf<String, Long>().withDefault { sequence }
    override fun bookmarkFor(bookmarkName: String) = Bookmark(bookmarkName, latest.getValue(bookmarkName)).also { bookmark -> latest.put(bookmark.name, bookmark.sequence + 1) }
    override fun bookmarksFor(bookmarkNames: Set<String>) = bookmarkNames.map { bookmarkFor(it) }.toSet()
    override fun save(bookmark: Bookmark) = fail("Should not be called")
}

fun eventProcessorFor(name: String, eventClasses: List<KClass<out DomainEvent>>, bookmarkStore: BookmarkStore) = object : AsyncEventProcessor<SpecificMetadata> {
    override val eventSource = alwaysFailsEventSource
    override val bookmarkStore = bookmarkStore
    override val bookmarkName = name
    override val sequencedEventProcessor = object : SequencedEventProcessor<SpecificMetadata> {
        override fun process(sequencedEvent: SequencedEvent<out SpecificMetadata>) = fail("Should not be called")
        override fun domainEventClasses(): List<KClass<out DomainEvent>> = eventClasses
    }
}

fun sequencedEventFor(domainEvent: DomainEvent, sequence: Long) = SequencedEvent(
    Event(
        id = UUID.randomUUID(),
        aggregateId = UUID.randomUUID(),
        aggregateSequence = 2,
        aggregateType = "aggregateType",
        createdAt = DateTime.now(),
        metadata = SpecificMetadata("specialField"),
        domainEvent = domainEvent,
    ),
    sequence,
)
