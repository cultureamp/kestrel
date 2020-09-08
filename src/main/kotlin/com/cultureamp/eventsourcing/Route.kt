package com.cultureamp.eventsourcing

import kotlin.reflect.KClass

data class Route<CC : CreationCommand, UC : UpdateCommand>(
    val creationCommandClass: KClass<CC>,
    val updateCommandClass: KClass<UC>,
    val aggregateConstructor: AggregateConstructor<CC, *, *, UC, *, *>
) {
    companion object {
        inline fun <reified CC : CreationCommand, reified UC : UpdateCommand> from(
            aggregateConstructor: AggregateConstructor<CC, *, *, UC, *, *>
        ): Route<CC, UC> = Route(CC::class, UC::class, aggregateConstructor)
    }
}