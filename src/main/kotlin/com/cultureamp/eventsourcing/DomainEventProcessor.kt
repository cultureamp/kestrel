package com.cultureamp.eventsourcing

import java.util.*

interface DomainEventProcessor<E : DomainEvent> {
    fun process(event: E, aggregateId: UUID)
}

interface DomainEventProcessorWithMetadata<E : DomainEvent, M : EventMetadata> {
    fun process(event: E, aggregateId: UUID, metadata: M, eventId: UUID)
}