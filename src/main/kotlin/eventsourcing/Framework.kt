package eventsourcing

import java.util.UUID
import kotlin.reflect.KFunction2
import kotlin.reflect.KFunction3
import kotlin.reflect.KFunction4

interface Projector<E : Event> {
    fun project(event: E)
}

interface DoubleProjector<A : Event, B : Event> {
    fun first(event: A)
    fun second(event: B)
}

interface ReadOnlyDatabase
interface ReadWriteDatabase

interface Aggregate {
    val aggregateId: UUID
    fun aggregateType(): String = this::class.simpleName!!
}

data class Configuration<CC : CreationCommand, CE : CreationEvent, UC : UpdateCommand, UE : UpdateEvent, A : Aggregate>(
    val created: (CE) -> A,
    val create: (CC) -> Either<CommandError, CE>,
    val updated: (A, UE) -> A,
    val update: (A, UC) -> Either<CommandError, List<UE>> = {_,_ -> Right.list() },
    val step: (A, CommandGateway) -> Either<CommandError, List<UE>> = {_,_ -> Right.list()}
)

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

fun <A, B, C> KFunction2<A, B, C>.partial(a: A): (B) -> C {
    return { b -> invoke(a, b) }
}

fun <A, B, C, D> KFunction3<A, B, C, D>.partial2(b: B): (A, C) -> D {
    return { a, c -> invoke(a, b, c) }
}

fun <A, B, C, D> ((A, B, C) -> D).partial2(b: B): (A, C) -> D {
    return { a, c -> invoke(a, b, c) }
}

fun <A, B, C, D, E> KFunction4<A, B, C, D, E>.partial2(b: B): (A, C, D) -> E {
    return { a, c, d -> invoke(a, b, c, d) }
}
