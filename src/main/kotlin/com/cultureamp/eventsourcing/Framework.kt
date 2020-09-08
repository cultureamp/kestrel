package com.cultureamp.eventsourcing

import org.joda.time.DateTime
import java.util.*
import kotlin.reflect.KClass
import kotlin.reflect.KFunction2
import kotlin.reflect.KFunction3
import kotlin.reflect.KFunction4

data class Configuration<CC : CreationCommand, CE : CreationEvent, Err : CommandError, UC : UpdateCommand, UE : UpdateEvent, A : BaseAggregate>(
    val creationCommandClass: KClass<CC>,
    val updateCommandClass: KClass<UC>,
    val create: (CC) -> Either<Err, CE>,
    val update: A.(UC) -> Either<Err, List<UE>>,
    val created: (CE) -> A,
    val updated: A.(UE) -> A,
    val aggregateType: A.() -> String
) {
    companion object {

        inline fun <reified CC : CreationCommand, CE : CreationEvent, Err : CommandError, reified UC : UpdateCommand, UE : UpdateEvent, reified A : BaseAggregate> from(
            noinline create: (CC) -> Either<Err, CE>,
            noinline update: A.(UC) -> Either<Err, List<UE>>,
            noinline created: (CE) -> A,
            noinline updated: A.(UE) -> A = { _ -> this },
            noinline aggregateType: A.() -> String = { this::class.simpleName!! }
        ): Configuration<CC, CE, Err, UC, UE, A> {
            return Configuration(CC::class, UC::class, create, update, created, updated, aggregateType)
        }

        inline fun <reified CC : CreationCommand, CE : CreationEvent, Err : CommandError, reified UC : UpdateCommand, UE : UpdateEvent, reified A : BaseAggregate> from(
            noinline create: (CC) -> Either<Err, CE>,
            noinline update: (UC) -> Either<Err, List<UE>>,
            instance: A,
            noinline aggregateType: A.() -> String = { this::class.simpleName!! }
        ): Configuration<CC, CE, Err, UC, UE, A> {
            return from(create, { update(it) }, { instance }, { instance }, aggregateType)
        }

        inline fun <reified CC : CreationCommand, CE : CreationEvent, Err : CommandError, reified UC : UpdateCommand, UE : UpdateEvent, reified Self : Aggregate<UC, UE, Err, Self>> from(
            aggregateConstructor: AggregateConstructor<CC, CE, Err, UC, UE, Self>
        ): Configuration<CC, CE, Err, UC, UE, Self> {
            val created = aggregateConstructor::created
            val create = aggregateConstructor::create
            val updated = Aggregate<UC, UE, Err, Self>::updated
            val update = Aggregate<UC, UE, Err, Self>::update
            val aggregateType = Aggregate<UC, UE, Err, Self>::aggregateType
            return from(create, update, created, updated, aggregateType)
        }

        inline fun <reified CC : CreationCommand, CE : CreationEvent, Err : CommandError, reified UC : UpdateCommand, UE : UpdateEvent, P, Self : AggregateWithProjection<UC, UE, Err, P, Self>> from(
            aggregateConstructor: AggregateConstructorWithProjection<CC, CE, Err, UC, UE, P, Self>,
            projection: P
        ): Configuration<CC, CE, Err, UC, UE, Aggregate<UC, UE, Err, *>> {
            return from<CC, CE, Err, UC, UE, Aggregate<UC, UE, Err, *>>(aggregateConstructor.partial(projection))
        }
    }

    fun create(creationCommand: CC, metadata: EventMetadata, eventStore: EventStore): Either<CommandError, Unit> = create(creationCommand).map { domainEvent ->
        val aggregate = created(domainEvent)
        val event = Event(
            id = UUID.randomUUID(),
            aggregateId = creationCommand.aggregateId,
            aggregateSequence = 1,
            createdAt = DateTime(),
            metadata = metadata,
            domainEvent = domainEvent)
        eventStore.sink(listOf(event), creationCommand.aggregateId, aggregate.aggregateType())
    }.flatten()

    @Suppress("UNCHECKED_CAST")
    fun update(updateCommand: UC, metadata: EventMetadata, events: List<Event>, eventStore: EventStore): Either<CommandError, Unit> {
        val creationEvent = events.first().domainEvent as CreationEvent
        val updateEvents = events.slice(1 until events.size).map { it.domainEvent as UpdateEvent }
        val aggregate = rehydrated(creationEvent as CE, updateEvents as List<UE>)
        return update(aggregate, updateCommand).map { domainEvents ->
            val updated = updated(aggregate, domainEvents)
            val offset = events.last().aggregateSequence + 1
            val createdAt = DateTime()
            val storableEvents = domainEvents.withIndex().map { (index, domainEvent) ->
                Event(
                    id = UUID.randomUUID(),
                    aggregateId = updateCommand.aggregateId,
                    aggregateSequence = offset + index,
                    createdAt = createdAt,
                    metadata = metadata,
                    domainEvent = domainEvent
                )
            }
            eventStore.sink(storableEvents, updateCommand.aggregateId, updated.aggregateType())
        }.flatten()
    }

    private fun rehydrated(creationEvent: CE, updateEvents: List<UE>): A = updated(created(creationEvent), updateEvents)

    private fun updated(initial: A, updateEvents: List<UE>): A =
        updateEvents.fold(initial) { aggregate, updateEvent -> updated(aggregate, updateEvent) }
}

data class EventListener(val handlers: Map<KClass<DomainEvent>, (DomainEvent, UUID, EventMetadata, UUID) -> Any?>) {
    fun handle(event: Event) {
        handlers.filterKeys { it.isInstance(event.domainEvent) }.values.forEach { it(event.domainEvent, event.aggregateId, event.metadata, event.id) }
    }

    @Suppress("UNCHECKED_CAST")
    companion object {
        inline fun <reified E : DomainEvent> from(noinline handle: (E, UUID) -> Any?): EventListener {
            val ignoreMetadataHandle = { domainEvent: E, aggregateId: UUID, _: EventMetadata, _: UUID -> handle(domainEvent, aggregateId) }
            val handler = (E::class to ignoreMetadataHandle) as Pair<KClass<DomainEvent>, (DomainEvent, UUID, EventMetadata, UUID) -> Any?>
            return EventListener(mapOf(handler))
        }

        inline fun <reified E : DomainEvent, reified M : EventMetadata> from(noinline handle: (E, UUID, M, UUID) -> Any?): EventListener {
            val handler = (E::class to handle) as Pair<KClass<DomainEvent>, (DomainEvent, UUID, EventMetadata, UUID) -> Any?>
            return EventListener(mapOf(handler))
        }

        fun compose(first: EventListener, second: EventListener): EventListener {
            return EventListener(first.handlers + second.handlers)
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

data class Event(
    val id: UUID,
    val aggregateId: UUID,
    val aggregateSequence: Long,
    val createdAt: DateTime,
    val metadata: EventMetadata,
    val domainEvent: DomainEvent
)

data class SequencedEvent(val event: Event, val sequence: Long)

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

interface AlreadyActionedCommandError : CommandError

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

fun <E, V, R> Either<E, V>.fold(left: (E) -> R, right: (V) -> R): R = when (this) {
    is Left -> left(this.error)
    is Right -> right(this.value)
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
