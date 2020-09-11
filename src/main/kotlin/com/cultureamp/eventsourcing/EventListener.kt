package com.cultureamp.eventsourcing

import com.cultureamp.common.asNestedSealedConcreteClasses
import java.util.*
import kotlin.reflect.KClass

data class EventListener(val handlers: Map<KClass<DomainEvent>, (DomainEvent, UUID, EventMetadata, UUID) -> Any?>) {
    val eventClasses = handlers.keys.flatMap { it.asNestedSealedConcreteClasses() }

    fun handle(event: Event) {
        handlers.filterKeys { it.isInstance(event.domainEvent) }.values.forEach { it(event.domainEvent, event.aggregateId, event.metadata, event.id) }
    }

    @Suppress("UNCHECKED_CAST")
    companion object {
        inline fun <reified E : DomainEvent> from(noinline handle: (E, UUID) -> Any?): EventListener {
            val ignoreMetadataHandle = { domainEvent: E, aggregateId: UUID, _: EventMetadata, _: UUID -> handle(domainEvent, aggregateId) }
            val handler = (E::class to ignoreMetadataHandle) as Pair<KClass<DomainEvent>, (DomainEvent, UUID, EventMetadata, UUID) -> Any?>
            return EventListener(mapOf(handler))
        }

        inline fun <reified E : DomainEvent, reified M : EventMetadata> from(noinline handle: (E, UUID, M, UUID) -> Any?): EventListener {
            val handler = (E::class to handle) as Pair<KClass<DomainEvent>, (DomainEvent, UUID, EventMetadata, UUID) -> Any?>
            return EventListener(mapOf(handler))
        }

        inline fun <reified E : DomainEvent> from(eventProcessor: EventProcessor<E>): EventListener {
            return from(eventProcessor::process)
        }

        inline fun <reified E : DomainEvent, reified M : EventMetadata> from(eventProcessor: EventProcessorWithMetadata<E, M>): EventListener {
            return from(eventProcessor::process)
        }

        fun compose(first: EventListener, second: EventListener): EventListener {
            return EventListener(first.handlers + second.handlers)
        }
    }
}