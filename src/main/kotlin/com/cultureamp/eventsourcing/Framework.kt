package com.cultureamp.eventsourcing

import org.joda.time.DateTime
import java.util.*
import kotlin.reflect.KFunction2
import kotlin.reflect.KFunction3
import kotlin.reflect.KFunction4
import kotlin.reflect.KFunction5

interface Command {
    val aggregateId: UUID
}

interface CreationCommand : Command {
    override val aggregateId: UUID
}

interface UpdateCommand : Command {
    override val aggregateId: UUID
}

data class Event<M: EventMetadata> (
    val id: UUID,
    val aggregateId: UUID,
    val aggregateSequence: Long,
    val aggregateType: String,
    val createdAt: DateTime,
    val metadata: M,
    val domainEvent: DomainEvent
)

data class SequencedEvent<M: EventMetadata>(val event: Event<M>, val sequence: Long)

open class EventMetadata

/**
 * Standard Culture Amp metadata. You probably want to consider logging these fields (but note they are optional).
 * If you know these fields at all times, you can probably create a new `Metadata` subclass.
 *
 * @property accountId: The aggregate ID of the account that owns the event’s aggregate instance
 * @property executorId: The aggregate ID of the user that executed the command that resulted in the event
 * @property causationId: If the event is the result of an action performed by a reactor, the ID of the causal event
 * @property correlationId: The identifier of a correlated action, request or event
 */
data class CAStandardMetadata(
    val accountId: UUID? = null,
    val executorId: UUID? = null,
    val correlationId: UUID? = null,
    val causationId: UUID? = null
): EventMetadata()

interface DomainEvent

interface CreationEvent : DomainEvent

interface UpdateEvent : DomainEvent

interface CommandError

interface DomainError: CommandError

interface AlreadyActionedCommandError : DomainError

interface AuthorizationCommandError : CommandError

interface RetriableError : CommandError

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

fun <E, V> Either<E, Either<E,V>>.flatten(): Either<E, V> = when (this) {
    is Left -> this
    is Right -> this.value
}

fun <E, V, R> Either<E, V>.flatMap(transform: (V) -> Either<E, R>): Either<E, R> = when (this) {
    is Left -> this
    is Right -> transform(this.value)
}

fun <E, V, R> Either<E, V>.fold(left: (E) -> R, right: (V) -> R): R = when (this) {
    is Left -> left(this.error)
    is Right -> right(this.value)
}

fun <A, B, C> KFunction2<A, B, C>.partial(a: A): (B) -> C {
    return { b -> invoke(a, b) }
}

fun <A, B, C> ((A, B) -> C).partial(a: A): (B) -> C {
    return { b -> invoke(a, b) }
}

fun <A, B, C, D> KFunction3<A, B, C, D>.partial(a: A): (B, C) -> D {
    return { b, c -> invoke(a, b, c) }
}

fun <A, B, C, D> ((A, B, C) -> D).partial(a: A): (B, C) -> D {
    return { b, c -> invoke(a, b, c) }
}


fun <A, B, C, D> KFunction3<A, B, C, D>.partial2(b: B): (A, C) -> D {
    return { a, c -> invoke(a, b, c) }
}

fun <A, B, C, D> ((A, B, C) -> D).partial2(b: B): (A, C) -> D {
    return { a, c -> invoke(a, b, c) }
}

fun <A, B, C, D, E> KFunction4<A, B, C, D, E>.partial(a: A): (B, C, D) -> E {
    return { b, c, d -> invoke(a, b, c, d) }
}

fun <A, B, C, D, E> KFunction4<A, B, C, D, E>.partial2(b: B): (A, C, D) -> E {
    return { a, c, d -> invoke(a, b, c, d) }
}

fun <A, B, C, D, E> ((A, B, C, D) -> E).partial2(b: B): (A, C, D) -> E {
    return { a, c, d -> invoke(a, b, c, d) }
}

fun <A, B, C, D, E, F> KFunction5<A, B, C, D, E, F>.partial2(b: B): (A, C, D, E) -> F {
    return { a, c, d, e -> invoke(a, b, c, d, e) }
}
