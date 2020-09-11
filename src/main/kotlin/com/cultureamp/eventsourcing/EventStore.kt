package com.cultureamp.eventsourcing

import java.util.UUID
import kotlin.reflect.KClass

interface EventSink {
    fun sink(newEvents: List<Event>, aggregateId: UUID, aggregateType: String): Either<CommandError, Unit>
}

interface EventSource {
    fun getAfter(sequence: Long, eventClasses: List<KClass<out DomainEvent>> = emptyList(), batchSize: Int = 100) : List<SequencedEvent>

    fun lastSequence(eventClasses: List<KClass<out DomainEvent>> = emptyList()): Long
}

interface EventStore : EventSink, EventSource {
    fun eventsFor(aggregateId: UUID): List<Event>
}

