package com.cultureamp.eventsourcing

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Blocks until every one of the given entity-processors has a bookmark at or past a target position, analogous to
 * [BlockingAsyncEventProcessorWaiter]. Handy for tests, and for the read-your-own-writes case where a request needs to
 * see its own change reflected in a projection.
 */
class BlockingAsyncEntityProcessorWaiter<E>(
    private val entityProcessors: List<BookmarkedEntityProcessor<E>>,
    private val maxWaitMs: Long = 5000,
    private val pollWaitMs: Long = 100,
    private val logger: (String) -> Unit = System.out::println,
) {
    fun waitUntilProcessed(position: EntityPosition) {
        if (entityProcessors.isEmpty()) return
        runBlocking {
            withTimeout(maxWaitMs) {
                while (anyLaggingFrom(position)) {
                    delay(pollWaitMs)
                }
            }
        }
    }

    private fun anyLaggingFrom(position: EntityPosition): Boolean {
        val bookmarkStoreToBookmarkNames = entityProcessors.map { it.bookmarkStore to it.bookmarkName }.groupBy { it.first }.mapValues { it.value.map { it.second }.toSet() }
        val bookmarks = bookmarkStoreToBookmarkNames.flatMap { it.key.bookmarksFor(it.value) }.toSet()
        val lagging = bookmarks.filter { it.position == null || it.position < position }
        if (lagging.isNotEmpty()) {
            logger("Waiting for entityProcessors to catch up. ${lagging.map { "${it.name}=${it.position}/$position" }}")
        }
        return lagging.isNotEmpty()
    }
}
