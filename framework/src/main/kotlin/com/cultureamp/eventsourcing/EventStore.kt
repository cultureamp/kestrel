package com.cultureamp.eventsourcing

import java.util.UUID

interface EventStore {
    val listeners: MutableList<EventListener>

    fun sink(newEvents: List<Event>, aggregateId: UUID, aggregateType: String): Either<CommandError, Unit>

    fun eventsFor(aggregateId: UUID): List<Event>

    // TODO this should be removed and implemented as separate threads/workers that poll the event-store
    fun notifyListeners(newEvents: List<Event>, aggregateId: UUID) {
        newEvents.forEach { event -> listeners.forEach { it.handle(event) } }
    }

    fun setup()

    /**
     * Replay all the events in the store on the project function, for an aggregateType
     */
    fun replay(aggregateType: String, project: (Event) -> Unit)

    fun getAfter(sequence: Long, batchSize: Int) : List<SequencedEvent>
}
