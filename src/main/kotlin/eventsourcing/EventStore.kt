package eventsourcing

import java.util.UUID

interface EventStore {
    var listeners: List<EventListener>

    fun sink(newEvents: List<Event>, aggregateId: UUID, aggregateType: String)

    fun eventsFor(aggregateId: UUID): List<Event>

    fun isTaken(aggregateId: UUID): Boolean

    // TODO this should be removed and implemented as separate threads/workers that poll the event-store
    fun notifyListeners(newEvents: List<Event>, aggregateId: UUID) {
        newEvents.forEach { event ->
            listeners.flatMap {it.handlers.filterKeys { it.isInstance(event.domainEvent) }.values}.forEach { it(event.domainEvent, aggregateId) }
        }
    }
}

class InMemoryEventStore : EventStore {
    val eventStore: HashMap<UUID, List<Event>> = hashMapOf()
    override lateinit var listeners: List<EventListener>

    override fun sink(newEvents: List<Event>, aggregateId: UUID, aggregateType: String) {
        val oldEvents = eventStore[aggregateId] ?: emptyList()
        eventStore[aggregateId] = oldEvents + newEvents
        notifyListeners(newEvents, aggregateId)
    }

    override fun eventsFor(aggregateId: UUID): List<Event> {
        return eventStore.getValue(aggregateId)
    }

    override fun isTaken(aggregateId: UUID): Boolean {
        return eventStore.containsKey(aggregateId)
    }
}