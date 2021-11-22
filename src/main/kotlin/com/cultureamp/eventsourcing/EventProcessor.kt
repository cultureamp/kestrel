package com.cultureamp.eventsourcing

import com.cultureamp.common.asNestedSealedConcreteClasses
import java.util.UUID
import kotlin.reflect.KClass

interface EventProcessor<in M : EventMetadata> {
    companion object {
        inline fun <reified E : DomainEvent> from(noinline process: (E, UUID) -> Any?) = CompositeDomainEventProcessor.from(process)
        inline fun <reified E : DomainEvent, reified M : EventMetadata> from(noinline process: (E, UUID, M, UUID) -> Any?) = CompositeDomainEventProcessor.from(process)
        inline fun <reified E : DomainEvent> from(domainEventProcessor: DomainEventProcessor<E>) = CompositeDomainEventProcessor.from(domainEventProcessor)
        inline fun <reified E : DomainEvent, reified M : EventMetadata> from(domainEventProcessor: DomainEventProcessorWithMetadata<E, M>) = CompositeDomainEventProcessor.from(domainEventProcessor)
        fun <M : EventMetadata> compose(first: CompositeDomainEventProcessor<M>, second: CompositeDomainEventProcessor<M>) = CompositeDomainEventProcessor.compose(first, second)
    }

    fun process(event: Event<out M>)
    fun domainEventClasses(): List<KClass<out DomainEvent>> = emptyList()
}

class CompositeDomainEventProcessor<in M : EventMetadata> (private val domainEventProcessors: Map<KClass<DomainEvent>, (DomainEvent, UUID, M, UUID) -> Any?>) : EventProcessor<M> {
    val eventClasses = domainEventProcessors.keys.flatMap { it.asNestedSealedConcreteClasses() }

    override fun domainEventClasses() = eventClasses

    override fun process(event: Event<out M>) {
        domainEventProcessors.filterKeys { it.isInstance(event.domainEvent) }.values.forEach { it(event.domainEvent, event.aggregateId, event.metadata, event.id) }
    }

    @Suppress("UNCHECKED_CAST")
    companion object {
        inline fun <reified E : DomainEvent> from(noinline process: (E, UUID) -> Any?): CompositeDomainEventProcessor<EventMetadata> {
            return from(DomainEventProcessor.from(process))
        }

        inline fun <reified E : DomainEvent, reified M : EventMetadata> from(noinline process: (E, UUID, M, UUID) -> Any?): CompositeDomainEventProcessor<M> {
            return from(DomainEventProcessorWithMetadata.from(process))
        }

        inline fun <reified E : DomainEvent> from(domainEventProcessor: DomainEventProcessor<E>): CompositeDomainEventProcessor<EventMetadata> {
            val ignoreMetadataHandle = { domainEvent: E, aggregateId: UUID, _: EventMetadata, _: UUID -> domainEventProcessor.process(domainEvent, aggregateId) }
            val handler = (E::class to ignoreMetadataHandle) as Pair<KClass<DomainEvent>, (DomainEvent, UUID, EventMetadata, UUID) -> Any?>
            return CompositeDomainEventProcessor(mapOf(handler))
        }

        inline fun <reified E : DomainEvent, reified M : EventMetadata> from(domainEventProcessor: DomainEventProcessorWithMetadata<E, M>): CompositeDomainEventProcessor<M> {
            val handle = { domainEvent: E, aggregateId: UUID, metadata: M, eventId: UUID -> domainEventProcessor.process(domainEvent, aggregateId, metadata, eventId) }
            val handler = (E::class to handle) as Pair<KClass<DomainEvent>, (DomainEvent, UUID, EventMetadata, UUID) -> Any?>
            return CompositeDomainEventProcessor(mapOf(handler))
        }

        fun <M : EventMetadata> compose(first: CompositeDomainEventProcessor<M>, second: CompositeDomainEventProcessor<M>): CompositeDomainEventProcessor<M> {
            return CompositeDomainEventProcessor(first.domainEventProcessors + second.domainEventProcessors)
        }
    }
}
