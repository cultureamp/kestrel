package com.cultureamp.eventsourcing

import java.util.UUID
import kotlin.reflect.KClass

interface EventSink<M: EventMetadata> {
    fun sink(newEvents: List<Event<M>>, aggregateId: UUID): Either<CommandError, Unit>
}

interface EventSource<out M: EventMetadata>  {
    fun getAfter(sequence: Long, eventClasses: Collection<KClass<out DomainEvent>> = emptySet(), batchSize: Int = 100) : List<SequencedEvent<out M>>

    fun lastSequence(eventClasses: Collection<KClass<out DomainEvent>> = emptySet()): Long
}

interface EventStore<M: EventMetadata> : EventSink<M>, EventSource<M> {
    fun eventsFor(aggregateId: UUID): List<Event<M>>
}

