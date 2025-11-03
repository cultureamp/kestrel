package com.cultureamp.eventsourcing

import java.util.*

interface DomainEventProcessor<in E : DomainEvent> {
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

interface DomainEventProcessorWithMetadata<in E : DomainEvent, in M : EventMetadata> {
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

interface DomainEventProcessorWithSequence<in E : DomainEvent, in M : EventMetadata> {
    fun process(event: E, aggregateId: UUID, metadata: M, eventId: UUID, sequence: Long)

    companion object {
        fun <E : DomainEvent, M : EventMetadata> from(process: (E, UUID, M, UUID, Long) -> Any?): DomainEventProcessorWithSequence<E, M> {
            return object : DomainEventProcessorWithSequence<E, M> {
                override fun process(event: E, aggregateId: UUID, metadata: M, eventId: UUID, sequence: Long) {
                    process(event, aggregateId, metadata, eventId, sequence)
                }
            }
        }
    }
}