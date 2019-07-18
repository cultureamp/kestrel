package eventsourcing

import java.util.UUID

interface CommandHandler<C: Command, E: Event, CE: CommandError> {
    fun handle(command: C): Either<CE, List<E>>
}

interface EventHandler<E: Event, T> {
    fun handle(event: E): T
}

interface Aggregate<C: UpdateCommand, E: UpdateEvent, CE: CommandError, Self : Aggregate<C, E, CE, Self>> :
    CommandHandler<C, E, CE>,
    EventHandler<E, Self> {
    val aggregateId: UUID
}

interface AggregateConstructor<C: CreationCommand, E: CreationEvent, CE: CommandError, AggregateType> :
    CommandHandler<C, E, CE>,
    EventHandler<E, AggregateType>

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
