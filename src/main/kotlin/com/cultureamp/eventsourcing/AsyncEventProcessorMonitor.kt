package com.cultureamp.eventsourcing

class AsyncEventProcessorMonitor(
    private val asyncEventProcessors: List<AsyncEventProcessor>,
    private val metrics: (Lag) -> Unit
) {
    fun run() {
        val lags = asyncEventProcessors.map {
            val bookmarkSequence = it.bookmarkStore.bookmarkFor(it.bookmarkName).sequence
            val lastSequence = it.eventSource.lastSequence(it.eventProcessor.eventClasses)
            Lag(
                name = it.bookmarkName,
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
