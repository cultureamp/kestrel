package com.cultureamp.eventsourcing

import kotlinx.coroutines.*
import kotlin.coroutines.CoroutineContext

/**
 * Error types that can occur during synchronous event processing
 */
sealed class SyncProcessorError
data class SyncProcessorTimeoutError(val timeoutMs: Long, val processor: String) : SyncProcessorError()
data class SyncProcessorException(val processor: String, val cause: Throwable) : SyncProcessorError()

/**
 * Processes events synchronously through a list of bookmarked event processors.
 * This is designed to run within the same database transaction as event storage,
 * providing true synchronous consistency.
 *
 * Unlike BlockingAsyncEventProcessorWaiter which polls for async processor progress,
 * this processor directly executes event processing and updates bookmarks inline.
 */
class BlockingSyncEventProcessor<M : EventMetadata>(
    private val eventProcessors: List<BookmarkedEventProcessor<M>>,
    private val timeoutMs: Long = 5000,
    private val logger: (String) -> Unit = System.out::println,
    private val coroutineContext: CoroutineContext = Dispatchers.IO
) {
    /**
     * Processes the given sequenced events through all relevant event processors.
     *
     * For each processor:
     * 1. Filters events to only those relevant to the processor (based on domainEventClasses)
     * 2. Processes each relevant event in sequence order
     * 3. Updates the processor's bookmark after each event
     *
     * All processors are executed in parallel (but their individual event processing is sequential).
     * If any processor fails or times out, the entire operation fails.
     *
     * @param sequencedEvents List of events with their sequence numbers from the database
     * @return Either SyncProcessorError (on failure) or Unit (on success)
     */
    fun processEvents(sequencedEvents: List<SequencedEvent<M>>): Either<SyncProcessorError, Unit> {
        if (sequencedEvents.isEmpty()) {
            return Right(Unit)
        }

        // Group processors by the events they're interested in
        val processorToRelevantEvents: Map<BookmarkedEventProcessor<M>, List<SequencedEvent<M>>> =
            eventProcessors.associateWith { processor ->
                val eventProcessorEventTypes = processor.eventProcessor.domainEventClasses().toSet()
                sequencedEvents.filter { event ->
                    eventProcessorEventTypes.isEmpty() || eventProcessorEventTypes.contains(event.event.domainEvent::class)
                }
            }.filterValues { it.isNotEmpty() }

        if (processorToRelevantEvents.isEmpty()) {
            return Right(Unit)
        }

        logger("Processing ${sequencedEvents.size} events through ${processorToRelevantEvents.size} sync processors")

        return try {
            // Process all processors sequentially to maintain transaction context
            // We avoid coroutines here to keep the database transaction context
            for ((processor, events) in processorToRelevantEvents) {
                val result = processEventsForProcessorSync(processor, events)
                if (result is Left) {
                    return result
                }
            }
            Right(Unit)
        } catch (e: Exception) {
            Left(SyncProcessorException("multiple processors", e))
        }
    }

    /**
     * Process events for a single processor sequentially, updating bookmarks after each event.
     * Synchronous version without coroutines to maintain transaction context.
     */
    private fun processEventsForProcessorSync(
        processor: BookmarkedEventProcessor<M>,
        events: List<SequencedEvent<M>>
    ): Either<SyncProcessorError, Unit> {
        return try {
            // Get current bookmark to start from
            val bookmarkStore = processor.bookmarkStore
            val currentBookmark = if (bookmarkStore is TransactionalBookmarkStore) {
                bookmarkStore.bookmarkForInCurrentTransaction(processor.bookmarkName)
            } else {
                bookmarkStore.bookmarkFor(processor.bookmarkName)
            }
            var latestBookmark = currentBookmark

            // Process events sequentially, updating bookmark after each
            for (event in events) {
                // Only process events newer than our current bookmark
                if (event.sequence > latestBookmark.sequence) {
                    // Process the event
                    processor.eventProcessor.process(event.event, event.sequence)

                    // Update bookmark to reflect this event has been processed
                    latestBookmark = latestBookmark.copy(sequence = event.sequence)

                    // Use transactional save if available to participate in the current transaction
                    if (bookmarkStore is TransactionalBookmarkStore) {
                        bookmarkStore.saveInCurrentTransaction(latestBookmark)
                    } else {
                        bookmarkStore.save(latestBookmark)
                    }
                }
            }

            Right(Unit)
        } catch (e: Exception) {
            Left(SyncProcessorException(processor.bookmarkName, e))
        }
    }
}