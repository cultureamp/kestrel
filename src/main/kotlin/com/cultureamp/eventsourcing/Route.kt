package com.cultureamp.eventsourcing

import kotlin.reflect.KClass

data class Route<CC : CreationCommand, UC : UpdateCommand, M: EventMetadata>(
    val creationCommandClass: KClass<CC>,
    val updateCommandClass: KClass<UC>,
    val executionContextClass: KClass<M>,
    val aggregateConstructor: AggregateConstructor<CC, *, *, UC, *, M, *>
) {
    companion object {
        inline fun <reified CC : CreationCommand, reified UC : UpdateCommand, reified M : EventMetadata> from(
            aggregateConstructor: AggregateConstructor<CC, *, *, UC, *, M, *>
        ): Route<CC, UC, M> = Route(CC::class, UC::class, M::class, aggregateConstructor)

        inline fun <reified CC: CreationCommand, CE: CreationEvent, Err: DomainError, reified M: EventMetadata, reified UC: UpdateCommand, UE: UpdateEvent, reified A: Any> from(
            noinline create: (CC, M) -> Either<Err, CE>,
            noinline update: A.(UC, M) -> Either<Err, List<UE>>,
            noinline created: (CE) -> A,
            noinline updated: A.(UE) -> A = { _ -> this },
            noinline aggregateType: () -> String = { A::class.simpleName!! }
        ): Route<CC, UC, M> = from(AggregateConstructor.from(create, update, created, updated, aggregateType))

        inline fun <reified CC : CreationCommand, CE : CreationEvent, Err : DomainError, reified UC : UpdateCommand, UE : UpdateEvent, reified M : EventMetadata, reified A : Any> fromStateless(
            noinline create: (CC, M) -> Either<Err, CE>,
            noinline update: (UC, M) -> Either<Err, List<UE>>,
            instance: A,
            noinline aggregateType: () -> String = { A::class.simpleName!! }
        ): Route<CC, UC, M> = from(AggregateConstructor.fromStateless(create, update, instance, aggregateType))
    }
}