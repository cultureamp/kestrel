package com.cultureamp.eventsourcing

import com.cultureamp.common.Action
import kotlin.random.Random

interface AsyncEventProcessor<M : EventMetadata> {
    val eventSource: EventSource<M>
    val bookmarkStore: BookmarkStore
    val bookmarkName: String
    val sequencedEventProcessor: SequencedEventProcessor<M>
}

class BatchedAsyncEventProcessor<M : EventMetadata>(
    override val eventSource: EventSource<M>,
    override val bookmarkStore: BookmarkStore,
    override val bookmarkName: String,
    override val sequencedEventProcessor: SequencedEventProcessor<M>,
    private val batchSize: Int = 1000,
    private val startLog: (Bookmark) -> Unit = { bookmark ->
        System.out.println("Polling for events for ${bookmark.name} from sequence ${bookmark.sequence}")
    },
    private val endLog: (Int, Bookmark) -> Unit = { count, bookmark ->
        if (count > 0 || Random.nextFloat() < 0.01) {
            System.out.println("Finished processing batch for ${bookmark.name}, $count events up to sequence ${bookmark.sequence}")
        }
    },
    private val upcasting: Boolean = true,
) : AsyncEventProcessor<M> {

    constructor(
        eventSource: EventSource<M>,
        bookmarkStore: BookmarkStore,
        bookmarkName: String,
        eventProcessor: EventProcessor<M>,
        batchSize: Int = 1000,
        startLog: (Bookmark) -> Unit = { bookmark ->
            System.out.println("Polling for events for ${bookmark.name} from sequence ${bookmark.sequence}")
        },
        endLog: (Int, Bookmark) -> Unit = { count, bookmark ->
            if (count > 0 || Random.nextFloat() < 0.01) {
                System.out.println("Finished processing batch for ${bookmark.name}, $count events up to sequence ${bookmark.sequence}")
            }
        },
        upcasting: Boolean = true,
    ) : this(
        eventSource, bookmarkStore, bookmarkName,
        object : SequencedEventProcessor<M> {
            override fun process(sequencedEvent: SequencedEvent<out M>) = eventProcessor.process(sequencedEvent.event)
            override fun domainEventClasses() = eventProcessor.domainEventClasses()
        },
        batchSize, startLog, endLog, upcasting
    )

    fun processOneBatch(): Action {
        val startBookmark = bookmarkStore.bookmarkFor(bookmarkName)

        startLog(startBookmark)

        var minLatency = Long.MAX_VALUE
        var maxLatency = 0L
        val (count, finalBookmark) = eventSource.getAfter(startBookmark.sequence, sequencedEventProcessor.domainEventClasses(), batchSize).foldIndexed(
            0 to startBookmark
        ) { index, _, sequencedEvent ->
            when(upcasting){
                true -> {
                    val domainEvent = sequencedEvent.event.domainEvent
                    val upcastEvent = domainEvent::class.annotations.filterIsInstance<UpcastEvent>()
                    if(upcastEvent.size == 1) {
                        sequencedEventProcessor.process(
                            SequencedEvent(
                                Event(
                                    id = sequencedEvent.event.id,
                                    aggregateId = sequencedEvent.event.aggregateId,
                                    aggregateSequence = sequencedEvent.event.aggregateSequence,
                                    aggregateType = sequencedEvent.event.aggregateType,
                                    createdAt = sequencedEvent.event.createdAt,
                                    metadata = sequencedEvent.event.metadata,
                                    domainEvent = upcastEvent.first().upcasting(domainEvent, sequencedEvent.event.metadata)
                                ),
                                sequencedEvent.sequence
                            )
                        )
                    }else{
                        sequencedEventProcessor.process(sequencedEvent)
                    }
                }
                false -> sequencedEventProcessor.process(sequencedEvent)
            }

            val updatedBookmark = startBookmark.copy(sequence = sequencedEvent.sequence)
            bookmarkStore.save(updatedBookmark)
            val latency = System.currentTimeMillis() - sequencedEvent.event.createdAt.millis
            minLatency = minLatency.coerceAtMost(latency)
            maxLatency = maxLatency.coerceAtLeast(latency)
            index + 1 to updatedBookmark
        }

        endLog(count, finalBookmark)

        return if (count >= batchSize) Action.Continue else Action.Wait
    }
}
