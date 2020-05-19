package com.cultureamp.eventsourcing

import com.cultureamp.common.Action
import com.cultureamp.common.ExponentialBackoff

class AsyncEventProcessor(
    private val eventStore: EventSource,
    private val bookmarkStore: BookmarkStore,
    private val bookmarkName: String,
    private val eventListener: EventListener,
    private val batchSize: Int = 1000
) {

    fun run() {
        ExponentialBackoff(onFailure = { error, _ -> error.printStackTrace() }).run(::projectOneBatch)
    }

    private fun projectOneBatch(): Action {
        val bookmark = bookmarkStore.findOrCreate(bookmarkName)

        System.out.println("Polling for events for ${bookmarkName} from ${bookmark.sequence}")

        val (count, sequence) = eventStore.getAfter(bookmark.sequence, batchSize).foldIndexed(
            0 to bookmark.sequence
        ) { index, _, sequencedEvent ->
            eventListener.handle(sequencedEvent.event)
            index + 1 to sequencedEvent.sequence
        }
        bookmarkStore.save(bookmarkName, bookmark.copy(sequence = sequence))

        System.out.println("Processed ${count} events for ${bookmarkName}")

        return if (count >= batchSize) Action.Continue else Action.Wait
    }
}
