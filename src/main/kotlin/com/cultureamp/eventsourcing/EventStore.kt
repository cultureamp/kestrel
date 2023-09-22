package com.cultureamp.eventsourcing

import arrow.core.Either
import java.util.*
import kotlin.reflect.KClass

interface EventSink<M: EventMetadata> {
    fun sink(newEvents: List<Event<M>>, aggregateId: UUID): Either<CommandError, Long>
}

interface EventSource<out M: EventMetadata>  {
    fun getAfter(sequence: Long, eventClasses: List<KClass<out DomainEvent>> = emptyList(), batchSize: Int = 100) : List<SequencedEvent<out M>>

    // NOTE: find out what `eventClasses = emptyList()` means in this context
    fun lastSequence(eventClasses: List<KClass<out DomainEvent>> = emptyList()): Long
}

interface EventStore<M: EventMetadata> : EventSink<M>, EventSource<M> {
    fun eventsFor(aggregateId: UUID): List<Event<M>>
}

