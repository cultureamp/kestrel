package com.cultureamp.eventsourcing

class AsyncEventProcessorMonitor<M : EventMetadata>(
    private val asyncEventProcessors: List<AsyncEventProcessor<M>>,
    private val metrics: (Lag) -> Unit,
) {
    fun run() {
        val lags = asyncEventProcessors.map {
            val bookmarkSequence = it.bookmarkStore.bookmarkFor(it.bookmarkName).sequence
            val lastSequence = it.eventsSequenceStats.lastSequence(it.eventProcessor.domainEventClasses())
            Lag(
                name = it.bookmarkName,
                bookmarkSequence = bookmarkSequence,
                lastSequence = lastSequence,
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
