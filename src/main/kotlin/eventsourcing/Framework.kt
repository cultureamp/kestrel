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

interface Aggregate<UC: UpdateCommand, UE: UpdateEvent, Err: CommandError, out Self : Aggregate<UC, UE, Err, Self>> {
    val aggregateId: UUID
    fun updated(event: UE): Self
    fun update(command: UC): Either<Err, List<UE>> // TODO this should probably be NonEmptyList<UE>
    fun aggregateType(): String = this::class.simpleName!!
}

interface AggregateWithProjection<UC: UpdateCommand, UE: UpdateEvent, Err: CommandError, P, Self : AggregateWithProjection<UC, UE, Err, P, Self>> {
    val aggregateId: UUID
    fun updated(event: UE): Self
    fun update(command: UC, projection: P): Either<Err, List<UE>> // TODO this should probably be NonEmptyList<UE>
    fun aggregateType(): String = this::class.simpleName!!

    fun curried(projection: P): Aggregate<UC, UE, Err, Aggregate<UC, UE, Err, *>> {
        return object:Aggregate<UC, UE, Err, Aggregate<UC, UE, Err, *>> {
            override val aggregateId = this@AggregateWithProjection.aggregateId

            override fun updated(event: UE): Aggregate<UC, UE, Err, *> {
                return this@AggregateWithProjection.updated(event).curried(projection)
            }

            override fun update(command: UC): Either<Err, List<UE>> {
                return update(command, projection)
            }

            override fun aggregateType(): String = this@AggregateWithProjection.aggregateType()
        }
    }
}

interface AggregateConstructor<CC: CreationCommand, CE: CreationEvent, Err: CommandError, UC: UpdateCommand, UE: UpdateEvent, Self: Aggregate<UC, UE, Err, Self>> {
    fun created(event: CE): Self
    fun create(command: CC): Either<Err, Pair<CE, List<UE>>>
    fun rehydrated(creationEvent: CE, vararg updateEvents: UE): Self {
        return updateEvents.fold(created(creationEvent)) { aggregate, updateEvent -> aggregate.updated(updateEvent) }
    }
}

interface AggregateConstructorWithProjection<CC: CreationCommand, CE: CreationEvent, Err: CommandError, UC: UpdateCommand, UE: UpdateEvent, P, Self : AggregateWithProjection<UC, UE, Err, P, Self>> {
    fun created(event: CE): Self
    fun create(command: CC, projection: P): Either<Err, Pair<CE, List<UE>>>
    fun rehydrated(creationEvent: CE, vararg updateEvents: UE): Self {
        return updateEvents.fold(created(creationEvent)) { aggregate, updateEvent -> aggregate.updated(updateEvent) }
    }
    fun curried(projection: P): AggregateConstructor<CC, CE, Err, UC, UE, Aggregate<UC, UE, Err, *>> {
        return object:AggregateConstructor<CC, CE, Err, UC, UE, Aggregate<UC, UE, Err, *>> {
            override fun created(event: CE): Aggregate<UC, UE, Err, *> {
                return this@AggregateConstructorWithProjection.created(event).curried(projection)
            }

            override fun create(command: CC): Either<Err, Pair<CE, List<UE>>> {
                return create(command, projection)
            }
        }
    }
}

interface Command {
    val aggregateId: UUID
}

interface CreationCommand : Command {
    override val aggregateId: UUID
}

interface UpdateCommand : Command {
    override val aggregateId: UUID
}

data class Step(override val aggregateId: UUID) : UpdateCommand

interface Event {
    val aggregateId: UUID
}

interface CreationEvent : Event

interface UpdateEvent : Event

interface CommandError

interface AlreadyActionedCommandError : CommandError

interface AuthorizationCommandError : CommandError

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
