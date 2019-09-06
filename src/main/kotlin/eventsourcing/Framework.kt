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

interface Aggregate<UC: UpdateCommand, UE: UpdateEvent, out Self : Aggregate<UC, UE, Self>> {
    val aggregateId: UUID
    fun updated(event: UE): Self
    fun update(command: UC): Either<CommandError, List<UE>>
    fun aggregateType(): String = this::class.simpleName!!
}

interface AggregateWithProjection<UC: UpdateCommand, UE: UpdateEvent, P, Self : AggregateWithProjection<UC, UE, P, Self>> {
    val aggregateId: UUID
    fun updated(event: UE): Self
    fun update(command: UC, projection: P): Either<CommandError, List<UE>>
    fun aggregateType(): String = this::class.simpleName!!

    fun curried(projection: P): Aggregate<UC, UE, Aggregate<UC, UE, *>> {
        return object:Aggregate<UC, UE, Aggregate<UC, UE, *>> {
            override val aggregateId = this@AggregateWithProjection.aggregateId

            override fun updated(event: UE): Aggregate<UC, UE, *> {
                return this@AggregateWithProjection.updated(event).curried(projection)
            }

            override fun update(command: UC): Either<CommandError, List<UE>> {
                return update(command, projection)
            }

            override fun aggregateType(): String = this@AggregateWithProjection.aggregateType()
        }
    }
}

interface AggregateConstructor<CC: CreationCommand, CE: CreationEvent, UC: UpdateCommand, UE: UpdateEvent, Self> {
    fun created(event: CE): Self
    fun create(command: CC): Either<CommandError, CE>
//    fun rehydrated(creationEvent: CE, vararg updateEvents: UE): Self {
//        return updateEvents.fold(created(creationEvent)) { aggregate, updateEvent -> aggregate.updated(updateEvent) }
//    }
}

open class FunctionHandleAggregateConstructor<CC: CreationCommand, CE: CreationEvent, UC: UpdateCommand, UE: UpdateEvent, Self>
    ( val createFn: (CC) -> Either<CommandError, CE>,
     val createdFn: (CE) -> Self,
     val updateFn: (Self, UC) -> Either<CommandError, List<UE>>,
     val updatedFn: (Self, UE) -> Self
) {
    fun created(event: CE): Self = createdFn(event)
    fun create(command: CC): Either<CommandError, CE> = createFn(command)
    fun updated(aggregate: Self, event: UE): Self = updatedFn(aggregate, event)
    fun update(aggregate: Self, command: UC): Either<CommandError, List<UE>> = updateFn(aggregate, command)
}

interface AggregateConstructorWithProjection<CC: CreationCommand, CE: CreationEvent, UC: UpdateCommand, UE: UpdateEvent, P, Self : AggregateWithProjection<UC, UE, P, Self>> {
    fun created(event: CE): Self
    fun create(command: CC, projection: P): Either<CommandError, CE>
    fun rehydrated(creationEvent: CE, vararg updateEvents: UE): Self {
        return updateEvents.fold(created(creationEvent)) { aggregate, updateEvent -> aggregate.updated(updateEvent) }
    }
    fun curried(projection: P): AggregateConstructor<CC, CE, UC, UE, Aggregate<UC, UE, *>> {
        return object:AggregateConstructor<CC, CE, UC, UE, Aggregate<UC, UE, *>> {
            override fun created(event: CE): Aggregate<UC, UE, *> {
                return this@AggregateConstructorWithProjection.created(event).curried(projection)
            }

            override fun create(command: CC): Either<CommandError, CE> {
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
