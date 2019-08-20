package eventsourcing

import java.util.UUID

interface EventStore {
    fun sink(aggregateType: String, events: List<Event>)

    fun eventsFor(aggregateId: UUID): Pair<CreationEvent, List<UpdateEvent>>
}

object InMemoryEventStore : EventStore {
    val eventStore: MutableMap<UUID, MutableList<Event>> = mutableMapOf<UUID, MutableList<Event>>().withDefault { mutableListOf() }

    override fun sink(aggregateType: String, events: List<Event>) {
        eventStore.getValue(events.first().aggregateId).addAll(events)
    }

    override fun eventsFor(aggregateId: UUID): Pair<CreationEvent, List<UpdateEvent>> {
        val events = eventStore.getValue(aggregateId)
        val creationEvent = events.first() as CreationEvent
        val updateEvents = events.drop(1) as List<UpdateEvent>
        return Pair(creationEvent, updateEvents)
    }
}