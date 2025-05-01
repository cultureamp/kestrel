package com.cultureamp.eventsourcing

import com.cultureamp.common.asNestedSealedConcreteClasses
import kotlin.reflect.KClass

interface EventTypeResolver {
    fun serialize(domainEventClass: Class<out DomainEvent>): EventTypeDescription
    fun deserialize(eventTypeDescription: EventTypeDescription): Class<out DomainEvent>
}

object CanonicalNameEventTypeResolver : EventTypeResolver {
    override fun serialize(domainEventClass: Class<out DomainEvent>) = EventTypeDescription(domainEventClass.canonicalName, null)
    override fun deserialize(eventTypeDescription: EventTypeDescription) = eventTypeDescription.eventType.asClass<DomainEvent>()!!
}

class PackageRemovingEventTypeResolver(aggregateTypeToEventType: Map<KClass<*>, KClass<out DomainEvent>>) : EventTypeResolver {
    private val eventTypeToEventClass = run {
        val allConcreteClasses = aggregateTypeToEventType.values.flatMap { it.asNestedSealedConcreteClasses().toSet() }
        val allConcreteClassSimpleNames = allConcreteClasses.map { it.simpleName!! }
        val duplicates = allConcreteClassSimpleNames.groupBy { it }.mapValues { it.value.size }.filter { it.value > 1 }
        if (duplicates.isNotEmpty()) {
            throw IllegalArgumentException("Event names ${duplicates.keys} exist in more than one aggregate")
        }
        allConcreteClassSimpleNames.zip(allConcreteClasses).toMap()
    }
    private val eventTypeToAggregateType: Map<String, String> = aggregateTypeToEventType.map { (aggregateType, eventType) ->
        eventType.asNestedSealedConcreteClasses().map { it.simpleName!! to aggregateType.simpleName!! }
    }.flatten().toMap()

    override fun deserialize(eventTypeDescription: EventTypeDescription) = eventTypeToEventClass.getValue(eventTypeDescription.eventType).java

    override fun serialize(domainEventClass: Class<out DomainEvent>) = EventTypeDescription(eventType = domainEventClass.simpleName, aggregateType = eventTypeToAggregateType[domainEventClass.simpleName])
}

data class EventTypeDescription(val eventType: String, val aggregateType: String?)

fun List<EventTypeDescription>.aggregateTypes() = mapNotNull { it.aggregateType }.distinct()
fun List<EventTypeDescription>.eventTypes() = map { it.eventType }.distinct()
