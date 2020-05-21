package com.cultureamp.eventsourcing

import java.util.UUID

interface EventSink {
    fun sink(newEvents: List<Event>, aggregateId: UUID, aggregateType: String): Either<CommandError, Unit>
}

interface EventSource {
    fun getAfter(sequence: Long, batchSize: Int) : List<SequencedEvent>

    fun lastSequence(): Long
}

interface EventStore : EventSink, EventSource {
    fun eventsFor(aggregateId: UUID): List<Event>

    /**
     * Replay all the events in the store on the project function, for an aggregateType
     */
    @Deprecated(
        "Doesn't batch, only used for big-bang synchronous projection rebuilds, which is an anti-pattern better done with AsyncEventProcessor",
        ReplaceWith("getAfter"))
    fun replay(aggregateType: String, project: (Event) -> Unit)
}

