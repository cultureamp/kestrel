package eventsourcing

import java.util.UUID
import kotlin.reflect.KClass
import kotlin.reflect.KFunction2
import kotlin.reflect.KFunction3
import kotlin.reflect.KFunction4

interface Aggregate

interface Aggregate2<UC: UpdateCommand, UE: UpdateEvent, Err: CommandError, out Self : Aggregate2<UC, UE, Err, Self>> : Aggregate {
    fun updated(event: UE): Self
    fun update(command: UC): Either<Err, List<UE>>
    fun aggregateType(): String = this::class.simpleName!!
}

interface AggregateWithProjection<UC: UpdateCommand, UE: UpdateEvent, Err: CommandError, P, Self : AggregateWithProjection<UC, UE, Err, P, Self>> {
    fun updated(event: UE): Self
    fun update(projection: P, command: UC): Either<Err, List<UE>>
    fun aggregateType(): String = this::class.simpleName!!

    fun partial(projection: P): Aggregate2<UC, UE, Err, Aggregate2<UC, UE, Err, *>> {
        return object:Aggregate2<UC, UE, Err, Aggregate2<UC, UE, Err, *>> {

            override fun updated(event: UE): Aggregate2<UC, UE, Err, *> {
                return this@AggregateWithProjection.updated(event).partial(projection)
            }

            override fun update(command: UC): Either<Err, List<UE>> {
                return update(projection, command)
            }

            override fun aggregateType(): String = this@AggregateWithProjection.aggregateType()
        }
    }
}

interface AggregateConstructor<CC: CreationCommand, CE: CreationEvent, Err: CommandError, UC: UpdateCommand, UE: UpdateEvent, Self: Aggregate2<UC, UE, Err, Self>> {
    fun created(event: CE): Self
    fun create(command: CC): Either<Err, CE>
}

interface AggregateConstructorWithProjection<CC: CreationCommand, CE: CreationEvent, Err: CommandError, UC: UpdateCommand, UE: UpdateEvent, P, Self : AggregateWithProjection<UC, UE, Err, P, Self>> {
    fun created(event: CE): Self
    fun create(projection: P, command: CC): Either<Err, CE>
    fun partial(projection: P): AggregateConstructor<CC, CE, Err, UC, UE, Aggregate2<UC, UE, Err, *>> {
        return object:AggregateConstructor<CC, CE, Err, UC, UE, Aggregate2<UC, UE, Err, *>> {
            override fun created(event: CE): Aggregate2<UC, UE, Err, *> {
                return this@AggregateConstructorWithProjection.created(event).partial(projection)
            }

            override fun create(command: CC): Either<Err, CE> {
                return create(projection, command)
            }
        }
    }
}

data class Configuration<CC : CreationCommand, CE : CreationEvent, Err : CommandError, UC : UpdateCommand, UE : UpdateEvent, A : Aggregate>(
    val creationCommandClass: KClass<CC>,
    val updateCommandClass: KClass<UC>,
    val create: (CC) -> Either<Err, CE>,
    val update: A.(UC) -> Either<Err, List<UE>>,
    val created: (CE) -> A,
    val updated: A.(UE) -> A,
    val aggregateType: A.() -> String
) {
    companion object {

        inline fun <reified CC : CreationCommand, CE : CreationEvent, Err : CommandError, reified UC : UpdateCommand, UE : UpdateEvent, reified A : Aggregate> from(
            noinline create: (CC) -> Either<Err, CE>,
            noinline update: A.(UC) -> Either<Err, List<UE>>,
            noinline created: (CE) -> A,
            noinline updated: A.(UE) -> A = { _ -> this },
            noinline aggregateType: A.() -> String = { this::class.simpleName!! }
        ): Configuration<CC, CE, Err, UC, UE, A> {
            return Configuration(CC::class, UC::class, create, update, created, updated, aggregateType)
        }

        inline fun <reified CC : CreationCommand, CE : CreationEvent, Err : CommandError, reified UC : UpdateCommand, UE : UpdateEvent, reified A : Aggregate> from(
            noinline create: (CC) -> Either<Err, CE>,
            noinline update: (UC) -> Either<Err, List<UE>>,
            instance: A,
            noinline aggregateType: A.() -> String = { this::class.simpleName!! }
        ): Configuration<CC, CE, Err, UC, UE, A> {
            return from(create, { update(it) }, { instance }, { instance }, aggregateType)
        }

        inline fun <reified CC : CreationCommand, CE : CreationEvent, Err : CommandError, reified UC : UpdateCommand, UE : UpdateEvent, reified Self : Aggregate2<UC, UE, Err, Self>> from(
            aggregateConstructor: AggregateConstructor<CC, CE, Err, UC, UE, Self>
        ): Configuration<CC, CE, Err, UC, UE, Self> {
            val created = aggregateConstructor::created
            val create = aggregateConstructor::create
            val updated = Aggregate2<UC, UE, Err, Self>::updated
            val update = Aggregate2<UC, UE, Err, Self>::update
            val aggregateType = Aggregate2<UC, UE, Err, Self>::aggregateType
            return from(create, update, created, updated, aggregateType)
        }

        inline fun <reified CC : CreationCommand, CE : CreationEvent, Err : CommandError, reified UC : UpdateCommand, UE : UpdateEvent, P, Self : AggregateWithProjection<UC, UE, Err, P, Self>> from(
            aggregateConstructor: AggregateConstructorWithProjection<CC, CE, Err, UC, UE, P, Self>,
            projection: P
        ): Configuration<CC, CE, Err, UC, UE, Aggregate2<UC, UE, Err, *>> {
            return from<CC, CE, Err, UC, UE, Aggregate2<UC, UE, Err, *>>(aggregateConstructor.partial(projection))
        }
    }

    fun create(creationCommand: CC, eventStore: EventStore) = create(creationCommand).map { event ->
        val aggregate = created(event)
        val events = listOf(event)
        eventStore.sink(events, creationCommand.aggregateId, aggregate.aggregateType())
    }

    @Suppress("UNCHECKED_CAST")
    fun update(updateCommand: UC, eventStore: EventStore): Either<Err, Unit> {
        val (creationEvent, updateEvents) = eventStore.eventsFor(updateCommand.aggregateId)
        val aggregate = rehydrated(creationEvent as CE, updateEvents as List<UE>)
        return update(aggregate, updateCommand).map { events ->
            val updated = updated(aggregate, events)
            eventStore.sink(events, updateCommand.aggregateId, updated.aggregateType())
        }
    }

    private fun rehydrated(creationEvent: CE, updateEvents: List<UE>): A = updated(created(creationEvent), updateEvents)

    private fun updated(initial: A, updateEvents: List<UE>): A =
        updateEvents.fold(initial) { aggregate, updateEvent -> updated(aggregate, updateEvent) }
}

data class EventListener(val handlers: Map<KClass<Event>, (Event, UUID) -> Any?>) {
    @Suppress("UNCHECKED_CAST")
    companion object {
        inline fun <reified E : Event> from(noinline handle: (E, UUID) -> Any?): EventListener {
            val handler = (E::class to handle) as Pair<KClass<Event>, (Event, UUID) -> Any?>
            return EventListener(mapOf(handler))
        }

        inline fun <reified A : Event, reified B : Event> from(noinline a: (A, UUID) -> Any?, noinline b: (B, UUID) -> Any?): EventListener {
            val first = (A::class to a) as Pair<KClass<Event>, (Event, UUID) -> Any?>
            val second = (B::class to b) as Pair<KClass<Event>, (Event, UUID) -> Any?>
            return EventListener(mapOf(first, second))
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

interface Event

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
