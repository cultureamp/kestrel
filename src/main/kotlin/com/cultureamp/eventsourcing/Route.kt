package com.cultureamp.eventsourcing

import kotlin.reflect.KClass

data class Route<CC : CreationCommand<Err>, UC : UpdateCommand<Err>, Err : DomainError>(
    val creationCommandClass: KClass<CC>,
    val updateCommandClass: KClass<UC>,
    val aggregateConstructor: AggregateConstructor<CC, *, Err, UC, *, *>
) {
    companion object {
        inline fun <reified CC : CreationCommand<Err>, reified UC : UpdateCommand<Err>, Err : DomainError> from(
            aggregateConstructor: AggregateConstructor<CC, *, Err, UC, *, *>
        ): Route<CC, UC, Err> = Route(CC::class, UC::class, aggregateConstructor)

        inline fun <reified CC: CreationCommand<Err>, CE: CreationEvent, Err: DomainError, reified UC: UpdateCommand<Err>, UE: UpdateEvent, reified A: Any> from(
            noinline create: (CC) -> Result<Err, CE>,
            noinline update: A.(UC) -> Result<Err, List<UE>>,
            noinline created: (CE) -> A,
            noinline updated: A.(UE) -> A = { _ -> this },
            noinline aggregateType: (A.() -> String) = { A::class.simpleName!! }
        ): Route<CC, UC, Err> = from(AggregateConstructor.from(create, update, created, updated, aggregateType))

        inline fun <reified CC : CreationCommand<Err>, CE : CreationEvent, Err : DomainError, reified UC : UpdateCommand<Err>, UE : UpdateEvent, reified A : Any> fromStateless(
            noinline create: (CC) -> Result<Err, CE>,
            noinline update: (UC) -> Result<Err, List<UE>>,
            instance: A,
            noinline aggregateType: (A.() -> String) = { A::class.simpleName!! }
        ): Route<CC, UC, Err> = from(AggregateConstructor.fromStateless(create, update, instance, aggregateType))
    }
}