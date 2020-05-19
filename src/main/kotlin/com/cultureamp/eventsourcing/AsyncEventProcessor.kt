package com.cultureamp.eventsourcing

import com.cultureamp.common.Action
import kotlin.random.Random

class AsyncEventProcessor(
    private val eventStore: EventSource,
    private val bookmarkStore: BookmarkStore,
    private val bookmarkName: String,
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
) {

    fun projectOneBatch(): Action {
        val startBookmark = bookmarkStore.bookmarkFor(bookmarkName)

        startLog(startBookmark)

        val (count, finalBookmark) = eventStore.getAfter(startBookmark.sequence, batchSize).foldIndexed(
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
