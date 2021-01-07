package com.cultureamp.eventsourcing

import com.cultureamp.common.asNestedSealedConcreteClasses
import java.util.*
import kotlin.reflect.KClass

interface EventProcessor<in M: EventMetadata> {
    fun process(event: Event<out M>)
    val eventClasses: Collection<KClass<out DomainEvent>>

    @Suppress("UNCHECKED_CAST")
    companion object {
        inline fun <reified E : DomainEvent> from(noinline process: (E, UUID) -> Any?): EventProcessor<EventMetadata> {
            return from(DomainEventProcessor.from(process))
        }

        inline fun <reified E : DomainEvent, reified M : EventMetadata> from(noinline process: (E, UUID, M, UUID) -> Any?): EventProcessor<M> {
            return from(DomainEventProcessorWithMetadata.from(process))
        }

        inline fun <reified E : DomainEvent> from(domainEventProcessor: DomainEventProcessor<E>): EventProcessor<EventMetadata> {
            val ignoreMetadataHandle = { domainEvent: E, aggregateId: UUID, _: EventMetadata, _: UUID -> domainEventProcessor.process(domainEvent, aggregateId) }
            val handler = (E::class to ignoreMetadataHandle) as Pair<KClass<DomainEvent>, (DomainEvent, UUID, EventMetadata, UUID) -> Any?>
            return EventTypeInferringEventProcessor(mapOf(handler))
        }

        inline fun <reified E : DomainEvent, reified M : EventMetadata> from(domainEventProcessor: DomainEventProcessorWithMetadata<E, M>): EventProcessor<M> {
            val handle = { domainEvent: E, aggregateId: UUID, metadata: M, eventId: UUID -> domainEventProcessor.process(domainEvent, aggregateId, metadata, eventId) }
            val handler = (E::class to handle) as Pair<KClass<DomainEvent>, (DomainEvent, UUID, EventMetadata, UUID) -> Any?>
            return EventTypeInferringEventProcessor(mapOf(handler))
        }

        fun <M : EventMetadata> compose(first: EventProcessor<M>, second: EventProcessor<M>): EventProcessor<M> {
            return object : EventProcessor<M> {
                override fun process(event: Event<out M>) {
                    first.process(event)
                    second.process(event)
                }

                override val eventClasses: List<KClass<out DomainEvent>> = first.eventClasses + second.eventClasses
            }
        }
    }
}

class EventTypeInferringEventProcessor<in M: EventMetadata> (private val domainEventProcessors: Map<KClass<DomainEvent>, (DomainEvent, UUID, M, UUID) -> Any?>) : EventProcessor<M> {
    override val eventClasses = domainEventProcessors.keys.flatMap { it.asNestedSealedConcreteClasses() }.toSet()

    override fun process(event: Event<out M>) {
        domainEventProcessors.filterKeys { it.isInstance(event.domainEvent) }.values.forEach { it(event.domainEvent, event.aggregateId, event.metadata, event.id) }
    }
}