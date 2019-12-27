package eventsourcing

import java.util.UUID

interface EventStore {
    fun sink(newEvents: List<Event>, aggregateId: UUID, aggregateType: String)

    fun eventsFor(aggregateId: UUID): Pair<CreationEvent, List<UpdateEvent>>

    fun isTaken(aggregateId: UUID): Boolean
}

class InMemoryEventStore : EventStore {
    val eventStore: HashMap<UUID, List<Event>> = hashMapOf()
    lateinit var listeners: List<EventListener>

    override fun sink(newEvents: List<Event>, aggregateId: UUID, aggregateType: String) {
        val oldEvents = eventStore[aggregateId] ?: emptyList()
        eventStore[aggregateId] = oldEvents + newEvents
        notifyListeners(newEvents, aggregateId)
    }

    @Suppress("UNCHECKED_CAST")
    override fun eventsFor(aggregateId: UUID): Pair<CreationEvent, List<UpdateEvent>> {
        val events = eventStore.getValue(aggregateId)
        val creationEvent = events.first() as CreationEvent
        val updateEvents = events.drop(1) as List<UpdateEvent>
        return Pair(creationEvent, updateEvents)
    }

    override fun isTaken(aggregateId: UUID): Boolean {
        return eventStore.containsKey(aggregateId)
    }

    // TODO this should be removed and implemented as separate threads/workers that poll the event-store
    private fun notifyListeners(newEvents: List<Event>, aggregateId: UUID) {
        newEvents.forEach { event ->
            listeners.flatMap {it.handlers.filterKeys { it.isInstance(event) }.values}.forEach { it(event, aggregateId) }
        }
    }
}