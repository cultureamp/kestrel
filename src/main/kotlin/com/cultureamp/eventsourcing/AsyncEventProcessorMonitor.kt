package com.cultureamp.eventsourcing

class AsyncEventProcessorMonitor<M: EventMetadata>(
    private val asyncEventProcessors: List<AsyncEventProcessor<M>>,
    private val metrics: (Lag) -> Unit
) {
    fun run() {
        val lags = asyncEventProcessors.map {
            val (bookmarkStore, bookmarkName, sequencedEventProcessor) = it.bookmarkedEventProcessor
            val bookmarkSequence = bookmarkStore.bookmarkFor(bookmarkName).sequence
            val lastSequence = it.eventSource.lastSequence(sequencedEventProcessor.domainEventClasses())
            Lag(
                name = bookmarkName,
                bookmarkSequence = bookmarkSequence,
                lastSequence = lastSequence
            )

        }

        lags.forEach {
            metrics(it)
        }
    }
}

data class Lag(val name: String, val bookmarkSequence: Long, val lastSequence: Long) {
    val lag: Long = lastSequence - bookmarkSequence
}
