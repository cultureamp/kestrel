package eventsourcing

import java.util.UUID

interface EventStore {
    var listeners: List<EventListener>

    fun sink(newEvents: List<Event>, aggregateId: UUID, aggregateType: String): Either<CommandError, Unit>

    fun eventsFor(aggregateId: UUID): List<Event>

    // TODO this should be removed and implemented as separate threads/workers that poll the event-store
    fun notifyListeners(newEvents: List<Event>, aggregateId: UUID) {
        newEvents.forEach { event ->
            listeners.flatMap {it.handlers.filterKeys { it.isInstance(event.domainEvent) }.values}.forEach { it(event.domainEvent, aggregateId) }
        }
    }
}
