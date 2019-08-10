package eventsourcing

import java.util.UUID

interface Projector<E: Event> {
    fun project(event: E)
}

interface DoubleProjector<A: Event, B: Event> {
    fun first(event: A)
    fun second(event: B)
}

interface TripleProjector<A: Event, B: Event, C: Event> {
    fun first(event: A)
    fun second(event: B)
    fun third(event: C)
}

interface ReadOnlyDatabase
interface ReadWriteDatabase

interface Aggregate<UC: UpdateCommand, UE: UpdateEvent, Err: CommandError, Self : Aggregate<UC, UE, Err, Self>> {
    val aggregateId: UUID
    fun updated(event: UE): Self
    fun update(command: UC): Either<Err, List<UE>>
}

interface AggregateWithProjection<UC: UpdateCommand, UE: UpdateEvent, Err: CommandError, P, Self : AggregateWithProjection<UC, UE, Err, P, Self>> {
    val aggregateId: UUID
    fun updated(event: UE): Self
    fun update(command: UC, projection: P): Either<Err, List<UE>>
}

interface AggregateConstructor<CC: CreationCommand, CE: CreationEvent, Err: CommandError, UC: UpdateCommand, UE: UpdateEvent, Self: Aggregate<UC, UE, Err, Self>> {
    fun created(event: CE): Self
    fun create(command: CC): Either<Err, List<CE>>
    fun rehydrated(creationEvent: CE, vararg updateEvents: UE): Self {
        return updateEvents.fold(created(creationEvent)) { aggregate, updateEvent -> aggregate.updated(updateEvent) }
    }
}

interface AggregateConstructorWithProjection<C: CreationCommand, CE: CreationEvent, Err: CommandError, UC: UpdateCommand, UE: UpdateEvent, P, Self : AggregateWithProjection<UC, UE, Err, P, Self>> {
    fun created(event: CE): Self
    fun create(command: C, projection: P): Either<Err, List<CE>>
    fun rehydrated(creationEvent: CE, vararg updateEvents: UE): Self {
        return updateEvents.fold(created(creationEvent)) { aggregate, updateEvent -> aggregate.updated(updateEvent) }
    }
}

//interface AggregateRootRepository {
//    fun <UC: UpdateCommand, UE: UpdateEvent, CommandError, Self : Aggregate<UC, UE, CommandError, Self>> get(aggregateId: UUID): Aggregate<UC, UE, CommandError, Aggregate<UC, UE, CommandError>>
//}

interface Command {
    val aggregateId: UUID
}

interface CreationCommand : Command {
    override val aggregateId: UUID
}

interface UpdateCommand : Command {
    override val aggregateId: UUID
}

interface Event {
    val aggregateId: UUID
}

interface CreationEvent : Event

interface UpdateEvent : Event

interface CommandError

//class AggregateRootRegistry(val list: List<AggregateConstructor<out CreationCommand, out CreationEvent, out CommandError, out Aggregate<out UpdateCommand, out UpdateEvent, out CommandError, *>>>) {
//    val commandToAggregateConstructor: Map<CreationCommand, AggregateConstructor<out CreationCommand, out CreationEvent, out CommandError, out Aggregate<out UpdateCommand, out UpdateEvent, out CommandError, *>>> =
//        TODO()
//
//    fun <CC : CreationCommand>aggregateRootConstructorFor(creationCommand: CC): AggregateConstructor<CC, *, *, *>? {
//        return null
//    }
//}

sealed class Either<out E, out V>
data class Left<E>(val error: E) : Either<E, Nothing>()
data class Right<V>(val value: V) : Either<Nothing, V>() {
    companion object {
        fun <V> list(vararg values: V): Either<Nothing, List<V>> = Right(listOf(*values))
    }
}

fun <E, V, R> Either<E, V>.map(transform: (V) -> R): Either<E, R> = when (this) {
    is Right -> Right(transform(this.value))
    is Left -> this
}
