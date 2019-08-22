package eventsourcing

import java.util.UUID

interface EventStore {
    fun sink(aggregateType: String, events: List<Event>)

    fun eventsFor(aggregateId: UUID): Pair<CreationEvent, List<UpdateEvent>>

    fun isTaken(aggregateId: UUID): Boolean
}

object InMemoryEventStore : EventStore {
    val eventStore: HashMap<UUID, List<Event>> = hashMapOf()

    override fun sink(aggregateType: String, newEvents: List<Event>) {
        val uuid = newEvents.first().aggregateId
        val oldEvents = eventStore[uuid] ?: emptyList()
        eventStore[uuid] = oldEvents + newEvents
    }

    override fun eventsFor(aggregateId: UUID): Pair<CreationEvent, List<UpdateEvent>> {
        val events = eventStore.getValue(aggregateId)
        val creationEvent = events.first() as CreationEvent
        val updateEvents = events.drop(1) as List<UpdateEvent>
        return Pair(creationEvent, updateEvents)
    }

    override fun isTaken(aggregateId: UUID): Boolean {
        return eventStore.containsKey(aggregateId)
    }

}