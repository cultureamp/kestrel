package com.cultureamp.eventsourcing


interface StatisticsCollector {
    fun eventProcessed(processor: AsyncEventProcessor<*> , event : SequencedEvent<*>, durationMs: Long)
}