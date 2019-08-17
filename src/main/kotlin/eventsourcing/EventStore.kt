package eventsourcing

import java.util.UUID

interface EventStore {
    fun sink(aggregateType: String, events: List<Event>)

    fun eventsFor(aggregateId: UUID): Pair<CreationEvent, List<UpdateEvent>>
}
