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
}

interface AggregateWithProjection<UC: UpdateCommand, UE: UpdateEvent, Err: CommandError, P, Self : AggregateWithProjection<UC, UE, Err, P, Self>> {
    val aggregateId: UUID
    fun updated(event: UE): Self
    fun update(command: UC, projection: P): Either<Err, List<UE>> // TODO this should probably be NonEmptyList<UE>
}

interface AggregateConstructor<CC: CreationCommand, CE: CreationEvent, Err: CommandError, UC: UpdateCommand, UE: UpdateEvent, Self: Aggregate<UC, UE, Err, Self>> {
    fun created(event: CE): Self
    fun create(command: CC): Either<Err, CE> // TODO this should probably be Pair<CE, MaybeEmptyList<UE>>
    fun rehydrated(creationEvent: CE, vararg updateEvents: UE): Self {
        return updateEvents.fold(created(creationEvent)) { aggregate, updateEvent -> aggregate.updated(updateEvent) }
    }
    fun aggregateType(): String = this.javaClass.typeName.substringBeforeLast('$')
}

interface AggregateConstructorWithProjection<C: CreationCommand, CE: CreationEvent, Err: CommandError, UC: UpdateCommand, UE: UpdateEvent, P, Self : AggregateWithProjection<UC, UE, Err, P, Self>> {
    fun created(event: CE): Self
    fun create(command: C, projection: P): Either<Err, CE> // TODO this should probably be Pair<CE, MaybeEmptyList<UE>>
    fun rehydrated(creationEvent: CE, vararg updateEvents: UE): Self {
        return updateEvents.fold(created(creationEvent)) { aggregate, updateEvent -> aggregate.updated(updateEvent) }
    }
    fun aggregateType(): String = this.javaClass.typeName.substringBeforeLast('$')
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

class CommandDispatcher(val constructorRepository: ConstructorRepository, val eventStore: EventStore) {
    fun dispatch(command: Command): Boolean = when(command){
        is CreationCommand -> construct(command)
        is UpdateCommand -> update(command)
        else -> TODO("Handle the case where command is both Update and Create type")
    }

    private fun construct(command: CreationCommand): Boolean { // TODO return proper error codes
        return constructorRepository.findFor(command)?.let { constructor -> // TODO case where a command can be used in two aggregates. Or otherwise re-implement as a Saga
            val result = constructor.create(command)
            when (result) {
                is Left -> false
                is Right -> {
                    // TODO verify that id doesn't already exist
                    eventStore.sink(constructor.aggregateType(), listOf(result.value))
                    true
                }
            }
        } ?: false
    }

    private fun update(command: UpdateCommand): Boolean { // TODO return proper error codes
        val (aggregateType, events) = eventStore.eventsFor(command.aggregateId)
        val (creationEvent, updateEvents) = events
        val constructor = constructorRepository.findFor(aggregateType)
        val initial = constructor.created(creationEvent)
        val aggregate = updateEvents.fold<UpdateEvent, Aggregate<UpdateCommand, UpdateEvent, CommandError, Aggregate<UpdateCommand, UpdateEvent, CommandError, *>>>(initial) { aggregate, updateEvent -> aggregate.updated(updateEvent) }
        val result = aggregate.update(command)
        return when (result) {
            is Left -> false
            is Right -> {
                // TODO verify that id doesn't already exist
                eventStore.sink(constructor.aggregateType(), result.value)
                true
            }
        }
    }

}

class EventStore {
    fun sink(aggregateType: String, events: List<Event>): Unit {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    fun eventsFor(aggregateId: UUID): Pair<String, Pair<CreationEvent, List<UpdateEvent>>> {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

}

// TODO how to handle aggregates with projections. Need to function-curry or something to inject dependencies
class ConstructorRepository {
    fun findFor(command: CreationCommand): AggregateConstructor<CreationCommand, CreationEvent, CommandError, UpdateCommand, UpdateEvent, Aggregate<UpdateCommand, UpdateEvent, CommandError, *>>? {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    fun findFor(aggregateType: String): AggregateConstructor<CreationCommand, CreationEvent, CommandError, UpdateCommand, UpdateEvent, Aggregate<UpdateCommand, UpdateEvent, CommandError, *>> {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
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
