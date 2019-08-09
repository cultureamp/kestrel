package survey.eventsourcing

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

interface Aggregate<C: UpdateCommand, E: UpdateEvent, CE: CommandError, Self : Aggregate<C, E, CE, Self>> {
    val aggregateId: UUID
    fun update(event: E): Self
    fun update(command: C): Either<CE, List<E>>
}

interface AggregateWithProjection<C: UpdateCommand, E: UpdateEvent, CE: CommandError, P, Self : AggregateWithProjection<C, E, CE, P, Self>> {
    val aggregateId: UUID
    fun update(event: E): Self
    fun update(projection: P, command: C): Either<CE, List<E>>
}

interface AggregateConstructor<C: CreationCommand, E: CreationEvent, CE: CommandError, AggregateType> {
    fun create(event: E): AggregateType
    fun create(command: C): Either<CE, List<E>>
}

interface AggregateConstructorWithProjection<C: CreationCommand, E: CreationEvent, CE: CommandError, P, AggregateType> {
    fun create(event: E): AggregateType
    fun create(projection: P, command: C): Either<CE, List<E>>
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

class AggregateRootRegistry(val list: List<AggregateConstructor<out CreationCommand, out CreationEvent, out CommandError, out Aggregate<out UpdateCommand, out UpdateEvent, out CommandError, *>>>) {
    val commandToAggregateConstructor: Map<CreationCommand, AggregateConstructor<out CreationCommand, out CreationEvent, out CommandError, out Aggregate<out UpdateCommand, out UpdateEvent, out CommandError, *>>> =
        TODO()

    fun <CC : CreationCommand>aggregateRootConstructorFor(creationCommand: CC): AggregateConstructor<CC, *, *, *>? {
        return null
    }
}

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
