package com.cultureamp.eventsourcing

/**
 * Synchronously processes events within a transaction using local processors.
 * Unlike BlockingAsyncEventProcessorWaiter which waits for external async processors,
 * this class directly executes the processing in the current thread.
 *
 * Includes catch-up mechanism to ensure processors are brought up to date before
 * processing new events.
 */
class BlockingSyncEventProcessorUpdater<M : EventMetadata>(
    private val eventProcessors: List<BookmarkedEventProcessor<M>>,
    private val eventSource: EventSource<M>
) {

    /**
     * Processes the given events using the configured processors.
     * First performs catch-up for any processors that are behind, then processes the new events.
     * Only processes events that are relevant to each processor based on their domainEventClasses().
     */
    fun processEvents(events: List<SequencedEvent<M>>) {
        if (events.isEmpty()) return

        for (processor in eventProcessors) {
            val eventProcessorEventTypes = processor.eventProcessor.domainEventClasses().toSet()

            // Get current bookmark for this processor
            val currentBookmark = processor.bookmarkStore.bookmarkFor(processor.bookmarkName)

            // Find the minimum sequence number from the new events that this processor cares about
            val relevantNewEvents = events.filter { event ->
                eventProcessorEventTypes.contains(event.event.domainEvent::class)
            }

            if (relevantNewEvents.isEmpty()) {
                continue // No relevant events for this processor
            }

            val minNewEventSequence = relevantNewEvents.minOf { it.sequence }

            // Check if we need to catch up - if there's a gap between bookmark and new events
            if (currentBookmark.sequence < minNewEventSequence - 1) {
                // Fetch missed events from bookmark sequence to just before the new events
                val missedEvents = eventSource.getAfter(
                    sequence = currentBookmark.sequence,
                    eventClasses = eventProcessorEventTypes.toList()
                ).filter { it.sequence < minNewEventSequence }

                // Process missed events first
                missedEvents.forEach { event ->
                    processor.eventProcessor.process(event.event, event.sequence)
                }
            }

            // Process the new relevant events
            relevantNewEvents.forEach { event ->
                processor.eventProcessor.process(event.event, event.sequence)
            }
        }
    }
}