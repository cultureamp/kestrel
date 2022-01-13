package com.cultureamp.eventsourcing

import com.cultureamp.common.asNestedSealedConcreteClasses
import java.util.UUID
import kotlin.reflect.KClass
import kotlin.reflect.KFunction2

interface SequencedEventProcessor<in E : DomainEvent, in M : EventMetadata> {
    fun process(sequencedEvent: SequencedEvent<out E, out M>)
    fun domainEventClasses(): List<KClass<DomainEvent>> = emptyList()
}

interface EventProcessor<in E : DomainEvent, in M : EventMetadata> {
    companion object {
        inline fun <reified E : DomainEvent, reified M : EventMetadata> from(noinline process: (Event<out E, out M>) -> Any?) = CompositeDomainEventProcessor.from(process)
        inline fun <reified E : DomainEvent> from(process: KFunction2<E, UUID, Any?>) = CompositeDomainEventProcessor.from(process)
        inline fun <reified E : DomainEvent, reified M : EventMetadata> from(noinline process: (E, UUID, M, UUID) -> Any?) = CompositeDomainEventProcessor.from(process)
        inline fun <reified E : DomainEvent> from(domainEventProcessor: DomainEventProcessor<E>) = CompositeDomainEventProcessor.from(domainEventProcessor)
        inline fun <reified E : DomainEvent, reified M : EventMetadata> from(domainEventProcessor: DomainEventProcessorWithMetadata<E, M>) = CompositeDomainEventProcessor.from(domainEventProcessor)
//        fun <M : EventMetadata> compose(first: CompositeDomainEventProcessor<DomainEvent, M>, second: CompositeDomainEventProcessor<out DomainEvent, M>) = CompositeDomainEventProcessor.compose(first, second)
    }

    fun process(event: Event<out E, out M>)
    fun domainEventClasses(): List<KClass<DomainEvent>> = emptyList()
}

class CompositeDomainEventProcessor<in E : DomainEvent, in M : EventMetadata> (internal val domainEventProcessors: Map<out KClass<E>, (Event<E, M>) -> Any?>) : EventProcessor<E, M> {
    private val eventClasses = domainEventProcessors.keys.flatMap { it.asNestedSealedConcreteClasses() }

    override fun domainEventClasses(): List<KClass<DomainEvent>> = eventClasses as List<KClass<DomainEvent>>

    override fun process(event: Event<in E, in M>) {
        domainEventProcessors.filterKeys { it.isInstance(event.domainEvent) }.values.forEach { it(event) }
    }

    @Suppress("UNCHECKED_CAST")
    companion object {
        inline fun <reified E : DomainEvent, reified M : EventMetadata> from(noinline process: (Event<in E, in M>) -> Any?): CompositeDomainEventProcessor<E, M> {
            return CompositeDomainEventProcessor(mapOf(E::class to process))
        }

        inline fun <reified E : DomainEvent> from(process: KFunction2<E, UUID, Any?>): CompositeDomainEventProcessor<E, EventMetadata> {
            return from(DomainEventProcessor.from(process))
        }

        inline fun <reified E : DomainEvent, reified M : EventMetadata> from(noinline process: (E, UUID, M, UUID) -> Any?): CompositeDomainEventProcessor<E, M> {
            return from(DomainEventProcessorWithMetadata.from(process))
        }

        inline fun <reified E : DomainEvent> from(domainEventProcessor: DomainEventProcessor<E>): CompositeDomainEventProcessor<E, EventMetadata> {
            val ignoreMetadataHandle = { event: Event<E, EventMetadata> -> domainEventProcessor.process(event.domainEvent, event.aggregateId) }
            val handler = (E::class to ignoreMetadataHandle) as Pair<KClass<E>, (Event<out E, out EventMetadata>) -> Any?>
            return CompositeDomainEventProcessor(mapOf(handler))
        }

        inline fun <reified E : DomainEvent, reified M : EventMetadata> from(domainEventProcessor: DomainEventProcessorWithMetadata<E, M>): CompositeDomainEventProcessor<E, M> {
            val handle = { event: Event<E, M> -> domainEventProcessor.process(event.domainEvent, event.aggregateId, event.metadata, event.id) }
            val handler = (E::class to handle) as Pair<KClass<E>, (Event<out E, out M>) -> Any?>
            return CompositeDomainEventProcessor(mapOf(handler))
        }

        fun <E : DomainEvent, M : EventMetadata> compose(first: CompositeDomainEventProcessor<in E, in M>, second: CompositeDomainEventProcessor<in E, in M>) =
            CompositeDomainEventProcessor(first.domainEventProcessors + second.domainEventProcessors)
    }
}
