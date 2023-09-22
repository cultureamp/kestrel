package com.cultureamp.eventsourcing

import arrow.core.NonEmptySet
import io.kotest.assertions.fail
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.TimeoutCancellationException
import org.joda.time.DateTime
import java.util.UUID
import kotlin.reflect.KClass

class BlockingAsyncEventProcessorWaiterTest : DescribeSpec({
    val fooEvent = sequencedEventFor(FooEvent("foo"), 100)
    val barEvent = sequencedEventFor(BarEvent("bar"), 105)
    val bazEvent = sequencedEventFor(BazEvent("baz"), 109)
    val sequencedEvents = listOf(fooEvent, barEvent, bazEvent)

    describe("BlockingAsyncEventProcessorWaiter") {
        it("loops until all bookmarks are up to date") {
            val firstBookmarkStore =
                bookmarkStoreCountingUpFrom(95, setOf(BookmarkName("FooProjector"), BookmarkName("FooBarProjector")))
            val secondBookmarkStore =
                bookmarkStoreCountingUpFrom(
                    110,
                    setOf(BookmarkName("BarProjector"), BookmarkName("AlreadyCaughtUpProjector"))
                )
            val fooEventProcessor =
                eventProcessorFor(BookmarkName("FooProjector"), listOf(FooEvent::class), firstBookmarkStore)
            val barEventProcessor =
                eventProcessorFor(BookmarkName("BarProjector"), listOf(BarEvent::class), secondBookmarkStore)
            val fooBarEventProcessor =
                eventProcessorFor(
                    BookmarkName("FooBarProjector"),
                    listOf(FooEvent::class, BarEvent::class),
                    firstBookmarkStore
                )
            val bazEventProcessor =
                eventProcessorFor(
                    BookmarkName("AlreadyCaughtUpProjector"),
                    listOf(BazEvent::class),
                    secondBookmarkStore
                )
            val quuxEventProcessor =
                eventProcessorFor(
                    BookmarkName("IrrelevantEventTypeProjector"),
                    listOf(QuuxEvent::class),
                    firstBookmarkStore
                )
            val eventProcessors = listOf(
                fooEventProcessor,
                barEventProcessor,
                fooBarEventProcessor,
                bazEventProcessor,
                quuxEventProcessor
            )

            val captured = mutableListOf<String>()
            val waiter = BlockingAsyncEventProcessorWaiter(eventProcessors, maxWaitMs = 5000L, pollWaitMs = 0L, logger = { captured.add(it) })
            waiter.waitUntilProcessed(sequencedEvents)
            captured shouldBe listOf(
                "Waiting for eventProcessors to catch up. [FooProjector=95/100, FooBarProjector=95/105]",
                "Waiting for eventProcessors to catch up. [FooProjector=96/100, FooBarProjector=96/105]",
                "Waiting for eventProcessors to catch up. [FooProjector=97/100, FooBarProjector=97/105]",
                "Waiting for eventProcessors to catch up. [FooProjector=98/100, FooBarProjector=98/105]",
                "Waiting for eventProcessors to catch up. [FooProjector=99/100, FooBarProjector=99/105]",
                "Waiting for eventProcessors to catch up. [FooBarProjector=100/105]",
                "Waiting for eventProcessors to catch up. [FooBarProjector=101/105]",
                "Waiting for eventProcessors to catch up. [FooBarProjector=102/105]",
                "Waiting for eventProcessors to catch up. [FooBarProjector=103/105]",
                "Waiting for eventProcessors to catch up. [FooBarProjector=104/105]",
            )
        }

        it("throws an exception when looping exceeds timeout") {
            val bookmarkStore = bookmarkStoreCountingUpFrom(0, setOf(BookmarkName("Arbitrary")))
            val eventProcessor = eventProcessorFor(BookmarkName("Arbitrary"), listOf(FooEvent::class), bookmarkStore)

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

fun bookmarkStoreCountingUpFrom(sequence: Long, allowedBookmarkNames: Set<BookmarkName>) = object : BookmarkStore {
    val latest = mutableMapOf<BookmarkName, Long>().withDefault { sequence }
    override fun bookmarkFor(bookmarkName: BookmarkName): Bookmark {
        if (!allowedBookmarkNames.contains(bookmarkName)) {
            fail("Bookmark store called with wrong bookmark name $bookmarkName")
        }
        return Bookmark(bookmarkName, latest.getValue(bookmarkName)).also { bookmark -> latest.put(bookmark.name, bookmark.sequence + 1) }
    }
    override fun bookmarksFor(bookmarkNames: NonEmptySet<BookmarkName>) = bookmarkNames.map { bookmarkFor(it) }.toSet()
    override fun save(bookmark: Bookmark) = fail("Should not be called")
}

fun eventProcessorFor(name: BookmarkName, eventClasses: List<KClass<out DomainEvent>>, bookmarkStore: BookmarkStore) = BookmarkedEventProcessor.from(
    bookmarkStore,
    name,
    object : SequencedEventProcessor<SpecificMetadata> {
        override fun process(sequencedEvent: SequencedEvent<out SpecificMetadata>) = fail("Should not be called")
        override fun domainEventClasses(): List<KClass<out DomainEvent>> = eventClasses
    },
)

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
