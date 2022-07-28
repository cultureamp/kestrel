package com.cultureamp.eventsourcing

import kotlin.reflect.KClass
import kotlin.reflect.full.functions

interface Upcast<in M : EventMetadata, in E : DomainEvent, out OE : DomainEvent> {
    fun upcast(event: E, metadata: M): OE
}

@Target(AnnotationTarget.CLASS)
@MustBeDocumented
annotation class UpcastEvent(val upcastType: KClass<out Upcast<*, *, *>>)

fun UpcastEvent.upcasting(event: DomainEvent, metadata: EventMetadata): DomainEvent = this.upcastType
    .functions
    .filterIndexed { index, _ -> index == 0 }
    .find {
            f ->
        f.name == "upcast" &&
            f.parameters.size == 3 &&
            f.parameters[1].type.classifier is KClass<*> &&
            (f.parameters[1].type.classifier as KClass<*>).isInstance(event)
    }
    ?.call(this.upcastType.objectInstance, event, metadata) as DomainEvent
