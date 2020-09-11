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

        inline fun <reified CC: CreationCommand, CE: CreationEvent, Err: DomainError, reified UC: UpdateCommand, UE: UpdateEvent, reified A: Any> from(
            noinline create: (CC) -> Either<Err, CE>,
            noinline update: A.(UC) -> Either<Err, List<UE>>,
            noinline created: (CE) -> A,
            noinline updated: A.(UE) -> A = { _ -> this },
            noinline aggregateType: (A.() -> String) = { A::class.simpleName!! }
        ): Route<CC, UC> = from(AggregateConstructor.from(create, update, created, updated, aggregateType))

        inline fun <reified CC : CreationCommand, CE : CreationEvent, Err : DomainError, reified UC : UpdateCommand, UE : UpdateEvent, reified A : Any> fromStateless(
            noinline create: (CC) -> Either<Err, CE>,
            noinline update: (UC) -> Either<Err, List<UE>>,
            instance: A,
            noinline aggregateType: (A.() -> String) = { A::class.simpleName!! }
        ): Route<CC, UC> = from(AggregateConstructor.fromStateless(create, update, instance, aggregateType))
    }
}