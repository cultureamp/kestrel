package com.cultureamp.eventsourcing

import com.cultureamp.common.Action
import kotlin.random.Random

interface EventProcessor {
    val eventSource: EventSource
    val bookmarkStore: BookmarkStore
    val bookmarkName: String
}

class AsyncEventProcessor(
    override val eventSource: EventSource,
    override val bookmarkStore: BookmarkStore,
    override val bookmarkName: String,
    private val eventListener: EventListener,
    private val batchSize: Int = 1000,
    private val startLog: (Bookmark) -> Unit = { bookmark ->
        System.out.println("Polling for events for ${bookmark.name} from sequence ${bookmark.sequence}")
    },
    private val endLog: (Int, Bookmark) -> Unit = { count, bookmark ->
        if (count > 0 || Random.nextFloat() < 0.01) {
            System.out.println("Finished processing batch for ${bookmark.name}, ${count} events up to sequence ${bookmark.sequence}")
        }
    }
): EventProcessor {

    fun processOneBatch(): Action {
        val startBookmark = bookmarkStore.bookmarkFor(bookmarkName)

        startLog(startBookmark)

        val (count, finalBookmark) = eventSource.getAfter(startBookmark.sequence, batchSize).foldIndexed(
            0 to startBookmark
        ) { index, _, sequencedEvent ->
            eventListener.handle(sequencedEvent.event)
            val updatedBookmark = startBookmark.copy(sequence = sequencedEvent.sequence)
            bookmarkStore.save(bookmarkName, updatedBookmark)
            index + 1 to updatedBookmark
        }

        endLog(count, finalBookmark)

        return if (count >= batchSize) Action.Continue else Action.Wait
    }
}
