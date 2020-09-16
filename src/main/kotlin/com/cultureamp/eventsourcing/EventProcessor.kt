package com.cultureamp.eventsourcing

import com.cultureamp.common.asNestedSealedConcreteClasses
import java.util.*
import kotlin.reflect.KClass

data class EventProcessor(val handlers: Map<KClass<DomainEvent>, (DomainEvent, UUID, EventMetadata, UUID) -> Any?>) {
    val eventClasses = handlers.keys.flatMap { it.asNestedSealedConcreteClasses() }

    fun handle(event: Event) {
        handlers.filterKeys { it.isInstance(event.domainEvent) }.values.forEach { it(event.domainEvent, event.aggregateId, event.metadata, event.id) }
    }

    @Suppress("UNCHECKED_CAST")
    companion object {
        inline fun <reified E : DomainEvent> from(noinline handle: (E, UUID) -> Any?): EventProcessor {
            val ignoreMetadataHandle = { domainEvent: E, aggregateId: UUID, _: EventMetadata, _: UUID -> handle(domainEvent, aggregateId) }
            val handler = (E::class to ignoreMetadataHandle) as Pair<KClass<DomainEvent>, (DomainEvent, UUID, EventMetadata, UUID) -> Any?>
            return EventProcessor(mapOf(handler))
        }

        inline fun <reified E : DomainEvent, reified M : EventMetadata> from(noinline handle: (E, UUID, M, UUID) -> Any?): EventProcessor {
            val handler = (E::class to handle) as Pair<KClass<DomainEvent>, (DomainEvent, UUID, EventMetadata, UUID) -> Any?>
            return EventProcessor(mapOf(handler))
        }

        inline fun <reified E : DomainEvent> from(domainEventProcessor: DomainEventProcessor<E>): EventProcessor {
            return from(domainEventProcessor::process)
        }

        inline fun <reified E : DomainEvent, reified M : EventMetadata> from(domainEventProcessor: DomainEventProcessorWithMetadata<E, M>): EventProcessor {
            return from(domainEventProcessor::process)
        }

        fun compose(first: EventProcessor, second: EventProcessor): EventProcessor {
            return EventProcessor(first.handlers + second.handlers)
        }
    }
}