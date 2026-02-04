package com.cultureamp.eventsourcing

/**
 * Synchronously processes events within a transaction using local processors.
 * Unlike BlockingAsyncEventProcessorWaiter which waits for external async processors,
 * this class directly executes the processing in the current thread.
 */
class BlockingSyncEventProcessorUpdater<M : EventMetadata>(
    private val eventProcessors: List<BookmarkedEventProcessor<M>>
) {

    /**
     * Processes the given events using the configured processors.
     * Only processes events that are relevant to each processor based on their domainEventClasses().
     */
    fun processEvents(events: List<SequencedEvent<M>>) {
        eventProcessors.forEach { processor ->
            val eventProcessorEventTypes = processor.eventProcessor.domainEventClasses().toSet()
            val relevantEvents = events.filter { event ->
                eventProcessorEventTypes.contains(event.event.domainEvent::class)
            }

            relevantEvents.forEach { event ->
                processor.eventProcessor.process(event.event, event.sequence)
            }
        }
    }
}