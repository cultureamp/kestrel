package com.cultureamp.eventsourcing

import com.cultureamp.common.asNestedSealedConcreteClasses
import kotlin.reflect.KClass

interface EventTypeResolver {
    fun serialize(domainEventClass: Class<out DomainEvent>): String
    fun deserialize(aggregateType: String, eventType: String): Class<out DomainEvent>
}

object CanonicalNameEventTypeResolver : EventTypeResolver {
    override fun serialize(domainEventClass: Class<out DomainEvent>) = domainEventClass.canonicalName
    override fun deserialize(aggregateType: String, eventType: String) = eventType.asClass<DomainEvent>()!!
}

class PackageRemovingEventTypeResolver(vararg eventClasses: KClass<out DomainEvent>) : EventTypeResolver {
    private val eventTypeToClass = run {
        val allConcreteClasses = eventClasses.flatMap { it.asNestedSealedConcreteClasses().toSet() }
        val allConcreteClassSimpleNames = allConcreteClasses.map { it.simpleName!! }
        val duplicates = allConcreteClassSimpleNames.groupBy { it }.mapValues { it.value.size }.filter { it.value > 1 }
        if (duplicates.isNotEmpty()) {
            throw IllegalArgumentException("Event names ${duplicates.keys} exist in more than one aggregate")
        }
        allConcreteClassSimpleNames.zip(allConcreteClasses).toMap()
    }

    override fun deserialize(aggregateType: String, eventType: String) = eventTypeToClass.getValue(eventType).java

    override fun serialize(domainEventClass: Class<out DomainEvent>) = domainEventClass.simpleName
}
