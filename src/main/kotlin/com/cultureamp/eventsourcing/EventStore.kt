package com.cultureamp.eventsourcing

import java.util.UUID
import kotlin.reflect.KClass

interface EventSink<M: EventMetadata> {
    fun sink(newEvents: List<Event<out DomainEvent, M>>, aggregateId: UUID): Either<CommandError, Unit>
}

interface EventSource<out M: EventMetadata>  {
    fun getAfter(sequence: Long, eventClasses: List<KClass<out DomainEvent>> = emptyList(), batchSize: Int = 100) : List<SequencedEvent<DomainEvent, out M>>

    fun lastSequence(eventClasses: List<KClass<out DomainEvent>> = emptyList()): Long
}

interface EventStore<M: EventMetadata> : EventSink<M>, EventSource<M> {
    fun eventsFor(aggregateId: UUID): List<Event<DomainEvent, M>>
}

