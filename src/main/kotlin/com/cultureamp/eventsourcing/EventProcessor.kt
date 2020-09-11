package com.cultureamp.eventsourcing

import java.util.*

interface EventProcessor<E : DomainEvent> {
    fun process(event: E, aggregateId: UUID)
}

interface EventProcessorWithMetadata<E : DomainEvent, M : EventMetadata> {
    fun process(event: E, aggregateId: UUID, metadata: M, eventId: UUID)
}