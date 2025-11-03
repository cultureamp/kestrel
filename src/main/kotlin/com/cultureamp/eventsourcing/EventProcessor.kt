package com.cultureamp.eventsourcing

import com.cultureamp.common.asNestedSealedConcreteClasses
import java.util.UUID
import kotlin.reflect.KClass


interface EventProcessor<in M : EventMetadata> {
    companion object {
        inline fun <reified E : DomainEvent> from(noinline process: (E, UUID) -> Any?) = CompositeDomainEventProcessor.from(process)
        inline fun <reified E : DomainEvent, reified M : EventMetadata> from(noinline process: (E, UUID, M, UUID) -> Any?) = CompositeDomainEventProcessor.from(process)
        inline fun <reified E : DomainEvent, reified M : EventMetadata> from(noinline process: (E, UUID, M, UUID, Long) -> Any?) = CompositeDomainEventProcessor.from(process)
        inline fun <reified E : DomainEvent> from(domainEventProcessor: DomainEventProcessor<E>) = CompositeDomainEventProcessor.from(domainEventProcessor)
        inline fun <reified E : DomainEvent, reified M : EventMetadata> from(domainEventProcessor: DomainEventProcessorWithMetadata<E, M>) = CompositeDomainEventProcessor.from(domainEventProcessor)
        inline fun <reified E : DomainEvent, reified M : EventMetadata> from(domainEventProcessor: DomainEventProcessorWithSequence<E, M>) = CompositeDomainEventProcessor.from(domainEventProcessor)
        fun <M : EventMetadata> compose(first: CompositeDomainEventProcessor<M>, vararg remainder: CompositeDomainEventProcessor<M>) = CompositeDomainEventProcessor.compose(first, *remainder)
    }

    fun process(event: Event<out M>, sequence: Long)
    fun domainEventClasses(): List<KClass<out DomainEvent>> = emptyList()
}

class CompositeDomainEventProcessor<in M : EventMetadata> (private val domainEventProcessors: List<Pair<KClass<DomainEvent>, (DomainEvent, UUID, M, UUID, Long) -> Any?>>) : EventProcessor<M> {
    val eventClasses = domainEventProcessors.flatMap { it.first.asNestedSealedConcreteClasses() }

    override fun domainEventClasses() = eventClasses

    override fun process(event: Event<out M>, sequence: Long) {
        domainEventProcessors.filter { it.first.isInstance(event.domainEvent) }.forEach { it.second(event.domainEvent, event.aggregateId, event.metadata, event.id, sequence) }
    }

    @Suppress("UNCHECKED_CAST")
    companion object {
        inline fun <reified E : DomainEvent> from(noinline process: (E, UUID) -> Any?): CompositeDomainEventProcessor<EventMetadata> {
            return from(DomainEventProcessor.from(process))
        }

        inline fun <reified E : DomainEvent, reified M : EventMetadata> from(noinline process: (E, UUID, M, UUID) -> Any?): CompositeDomainEventProcessor<M> {
            return from(DomainEventProcessorWithMetadata.from(process))
        }

        inline fun <reified E : DomainEvent, reified M : EventMetadata> from(noinline process: (E, UUID, M, UUID, Long) -> Any?): CompositeDomainEventProcessor<M> {
            return from(DomainEventProcessorWithSequence.from(process))
        }

        inline fun <reified E : DomainEvent> from(domainEventProcessor: DomainEventProcessor<E>): CompositeDomainEventProcessor<EventMetadata> {
            val handle: (DomainEvent, UUID, EventMetadata, UUID, Long) -> Any? = { domainEvent, aggregateId, _, _, _ ->
                domainEventProcessor.process(domainEvent as E, aggregateId)
            }
            val handler = (E::class to handle) as Pair<KClass<DomainEvent>, (DomainEvent, UUID, EventMetadata, UUID, Long) -> Any?>
            return CompositeDomainEventProcessor(listOf(handler))
        }

        inline fun <reified E : DomainEvent, reified M : EventMetadata> from(domainEventProcessor: DomainEventProcessorWithMetadata<E, M>): CompositeDomainEventProcessor<M> {
            val handle: (DomainEvent, UUID, M, UUID, Long) -> Any? = { domainEvent, aggregateId, metadata, eventId, _ ->
                domainEventProcessor.process(domainEvent as E, aggregateId, metadata, eventId)
            }
            val handler = (E::class to handle) as Pair<KClass<DomainEvent>, (DomainEvent, UUID, M, UUID, Long) -> Any?>
            return CompositeDomainEventProcessor(listOf(handler))
        }

        inline fun <reified E : DomainEvent, reified M : EventMetadata> from(domainEventProcessor: DomainEventProcessorWithSequence<E, M>): CompositeDomainEventProcessor<M> {
            val handle: (DomainEvent, UUID, M, UUID, Long) -> Any? = { domainEvent, aggregateId, metadata, eventId, sequence ->
                domainEventProcessor.process(domainEvent as E, aggregateId, metadata, eventId, sequence)
            }
            val handler = (E::class to handle) as Pair<KClass<DomainEvent>, (DomainEvent, UUID, M, UUID, Long) -> Any?>
            return CompositeDomainEventProcessor(listOf(handler))
        }

        fun <M : EventMetadata> compose(first: CompositeDomainEventProcessor<M>, vararg remainder: CompositeDomainEventProcessor<M>): CompositeDomainEventProcessor<M> {
            return CompositeDomainEventProcessor(first.domainEventProcessors + remainder.flatMap { it.domainEventProcessors })
        }
    }
}
