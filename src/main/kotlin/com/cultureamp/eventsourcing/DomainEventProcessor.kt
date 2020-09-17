package com.cultureamp.eventsourcing

import java.util.*

interface DomainEventProcessor<E : DomainEvent> {
    fun process(event: E, aggregateId: UUID)

    companion object {
        fun <E : DomainEvent> from(process: (E, UUID) -> Any?): DomainEventProcessor<E> {
            return object : DomainEventProcessor<E> {
                override fun process(event: E, aggregateId: UUID) {
                    process(event, aggregateId)
                }
            }
        }
    }
}

interface DomainEventProcessorWithMetadata<E : DomainEvent, M : EventMetadata> {
    fun process(event: E, aggregateId: UUID, metadata: M, eventId: UUID)

    companion object {
        fun <E : DomainEvent, M : EventMetadata> from(process: (E, UUID, M, UUID) -> Any?): DomainEventProcessorWithMetadata<E, M> {
            return object : DomainEventProcessorWithMetadata<E, M> {
                override fun process(event: E, aggregateId: UUID, metadata: M, eventId: UUID) {
                    process(event, aggregateId, metadata, eventId)
                }
            }
        }
    }
}