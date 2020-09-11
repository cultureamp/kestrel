package com.cultureamp.eventsourcing

class EventProcessorMonitor(
    private val eventProcessors: List<AsyncEventProcessor>,
    private val metrics: (Lag) -> Unit
) {
    fun run() {
        val eventProcessorLags = eventProcessors.map {
            val bookmarkSequence = it.bookmarkStore.bookmarkFor(it.bookmarkName).sequence
            val lastSequence = it.eventSource.lastSequence(it.eventListener.eventClasses)
            Lag(
                name = it.bookmarkName,
                bookmarkSequence = bookmarkSequence,
                lastSequence = lastSequence
            )

        }

        eventProcessorLags.forEach {
            metrics(it)
        }
    }
}

data class Lag(val name: String, val bookmarkSequence: Long, val lastSequence: Long) {
    val lag: Long = lastSequence - bookmarkSequence
}
