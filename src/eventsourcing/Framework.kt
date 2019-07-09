package eventsourcing

import java.util.UUID

interface Aggregate<UC: UpdateCommand, UE: UpdateEvent, E: Error, Self : Aggregate<UC, UE, E, Self>> {
    val aggregateId: UUID
    fun update(event: UE): Self
    fun handle(command: UC): Result<UE, E>
}

interface AggregateConstructor<CC: CreationCommand, CE: CreationEvent, E: Error, AggregateType> {
    fun create(event: CE): AggregateType
    fun handle(command: CC): Result<CreationEvent, E>
}

//interface AggregateRootRepository {
//    fun <UC: UpdateCommand, UE: UpdateEvent, Error, Self : Aggregate<UC, UE, Error, Self>> get(aggregateId: UUID): Aggregate<UC, UE, Error, Aggregate<UC, UE, Error>>
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

interface Event

interface CreationEvent : Event {
    val aggregateId: UUID
}

interface UpdateEvent : Event

interface Error

class AggregateRootRegistry(val list: List<AggregateConstructor<out CreationCommand, out CreationEvent, out Error, out Aggregate<out UpdateCommand, out UpdateEvent, out Error, *>>>) {
    val commandToAggregateConstructor: Map<CreationCommand, AggregateConstructor<out CreationCommand, out CreationEvent, out Error, out Aggregate<out UpdateCommand, out UpdateEvent, out Error, *>>> =
        list.map {  }

    fun <CC : CreationCommand>aggregateRootConstructorFor(creationCommand: CC): AggregateConstructor<CC, *, *, *>? {
        return null
    }
}



sealed class Result<out V, out E> {
    data class Success<V>(val values: List<V>) : Result<V, Nothing>() {
        constructor(vararg values: V) : this(listOf(*values))
    }

    data class Failure<E>(val error: E) : Result<Nothing, E>()
}