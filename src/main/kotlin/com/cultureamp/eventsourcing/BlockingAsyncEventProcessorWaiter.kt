package com.cultureamp.eventsourcing

import arrow.core.toNonEmptySetOrNull
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class BlockingAsyncEventProcessorWaiter<M : EventMetadata>(
    private val eventProcessors: List<BookmarkedEventProcessor<M>>,
    private val maxWaitMs: Long = 5000,
    private val pollWaitMs: Long = 100,
    private val logger: (String) -> Unit = System.out::println,
) {
    fun waitUntilProcessed(events: List<SequencedEvent<M>>) {
        val projectorToMaxRelevantSequence: Map<BookmarkedEventProcessor<M>, Long> = eventProcessors.associateWith { eventProcessor ->
            val eventProcessorEventTypes = eventProcessor.sequencedEventProcessor.domainEventClasses().toSet()
            val relevantSequencedEvents = events.filter { eventProcessorEventTypes.contains(it.event.domainEvent::class) }
            val maxRelevantSequence = relevantSequencedEvents.lastOrNull()?.let { it.sequence }
            maxRelevantSequence
        }.filterNotNullValues()
        if (projectorToMaxRelevantSequence.isNotEmpty()) {
            runBlocking {
                withTimeout(maxWaitMs) {
                    while (anyLaggingFrom(projectorToMaxRelevantSequence)) {
                        delay(pollWaitMs)
                    }
                }
            }
        }
    }

    private fun anyLaggingFrom(projectorToMaxRelevantSequence: Map<BookmarkedEventProcessor<M>, Long>): Boolean {
        val bookmarkStoreToBookmarkNames = projectorToMaxRelevantSequence.keys
            .map { it.bookmarkStore to it.bookmarkName }
            .groupBy { it.first }
            .mapValues { it.value.map { it.second }.toNonEmptySetOrNull()!! }
        val bookmarks = bookmarkStoreToBookmarkNames.flatMap { it.key.bookmarksFor(it.value) }.toSet()
        val desired = projectorToMaxRelevantSequence.mapKeys { it.key.bookmarkName }
        val actual = bookmarks.associate { it.name to it.sequence }
        val diff = desired.map { it.key to it.value - (actual[it.key] ?: 0) }.toMap()
        val lagging = diff.filter { it.value > 0 }
        if (lagging.isNotEmpty()) {
            logger("Waiting for eventProcessors to catch up. ${lagging.map { "${it.key}=${(actual[it.key] ?: 0)}/${desired[it.key]}" }}")
        }
        return lagging.isNotEmpty()
    }
}

@Suppress("UNCHECKED_CAST")
fun <K, V> Map<K, V?>.filterNotNullValues(): Map<K, V> =
    filterValues { it != null } as Map<K, V>
