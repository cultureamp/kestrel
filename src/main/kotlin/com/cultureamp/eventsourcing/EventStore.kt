package com.cultureamp.eventsourcing

import java.util.UUID
import kotlin.reflect.KClass

interface EventSink<M : EventMetadata> {
    fun sink(newEvents: List<Event<M>>, aggregateId: UUID): Either<CommandError, Long>
}

interface EventSource<out M : EventMetadata> {
    fun getAfter(sequence: Long, eventClasses: List<KClass<out DomainEvent>> = emptyList(), batchSize: Int = 100): List<SequencedEvent<out M>>
}

interface EventStore<M : EventMetadata> : EventSink<M>, EventSource<M> {
    fun eventsFor(aggregateId: UUID): List<Event<M>>
}
