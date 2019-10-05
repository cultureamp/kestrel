package eventsourcing

import java.util.UUID
import kotlin.reflect.KClass
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

interface ReadOnlyDatabase {
    fun <T : Any> find(type: KClass<T>, aggregateId: UUID): T
    fun <T : Any> exists(type: KClass<T>, predicate: (T) -> Boolean): Boolean
}

interface ReadWriteDatabase : ReadOnlyDatabase {
    fun insert(id: UUID, item: Any)
    fun upsert(id: UUID, item: Any)
    fun delete(id: UUID)
}
class InMemoryReadWriteDatabase : ReadWriteDatabase {
    val items: HashMap<UUID, Any> = hashMapOf()

    override fun insert(id: UUID, item: Any) {
        items[id] = item // TODO fail if already exists
    }

    override fun upsert(id: UUID, item: Any) {
        items[id] = item
    }

    override fun delete(id: UUID) {
        items.remove(id)
    }

    override fun <T : Any> find(type: KClass<T>, aggregateId: UUID): T {
        return items.filterKeys { it == aggregateId }.values.filterIsInstance(type.java).first()
    }

    override fun <T : Any> exists(type: KClass<T>, predicate: (T) -> Boolean): Boolean {
        return items.values.filterIsInstance(type.java).any(predicate)
    }
}

interface Aggregate {
    val aggregateId: UUID
    fun aggregateType(): String = this::class.simpleName!!
}

interface Aggregate2<UC: UpdateCommand, UE: UpdateEvent, Err: CommandError, out Self : Aggregate2<UC, UE, Err, Self>> : Aggregate {
    fun updated(event: UE): Self
    fun update(command: UC): Either<Err, List<UE>>
}

interface AggregateWithProjection<UC: UpdateCommand, UE: UpdateEvent, Err: CommandError, P, Self : AggregateWithProjection<UC, UE, Err, P, Self>> {
    val aggregateId: UUID
    fun updated(event: UE): Self
    fun update(projection: P, command: UC): Either<Err, List<UE>>
    fun aggregateType(): String = this::class.simpleName!!

    fun partial(projection: P): Aggregate2<UC, UE, Err, Aggregate2<UC, UE, Err, *>> {
        return object:Aggregate2<UC, UE, Err, Aggregate2<UC, UE, Err, *>> {
            override val aggregateId = this@AggregateWithProjection.aggregateId

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

data class Configuration<CC : CreationCommand, CE : CreationEvent, Err: CommandError, UC : UpdateCommand, UE : UpdateEvent, A : Aggregate>(
    val creationCommandClass: KClass<CC>,
    val updateCommandClass: KClass<UC>,
    val created: (CE) -> A,
    val create: (CC) -> Either<Err, CE>,
    val updated: (A, UE) -> A,
    val update: (A, UC) -> Either<Err, List<UE>>
) {
    companion object {

        inline fun <reified CC : CreationCommand, CE : CreationEvent, Err : CommandError, reified UC : UpdateCommand, UE : UpdateEvent, A : Aggregate> from(
            noinline created: (CE) -> A,
            noinline create: (CC) -> Either<Err, CE>,
            noinline updated: (A, UE) -> A,
            noinline update: (A, UC) -> Either<Err, List<UE>>
        ): Configuration<CC, CE, Err, UC, UE, A> {
            return Configuration(CC::class, UC::class, created, create, updated, update)
        }

        inline fun <reified CC : CreationCommand, CE : CreationEvent, Err: CommandError, reified UC : UpdateCommand, UE : UpdateEvent, Self: Aggregate2<UC, UE, Err, Self>> from(
            aggregateConstructor: AggregateConstructor<CC, CE, Err, UC, UE, Self>
        ): Configuration<CC, CE, Err, UC, UE, Self> {
            val created = aggregateConstructor::created
            val create = aggregateConstructor::create
            val updated = Aggregate2<UC, UE, Err, Self>::updated
            val update = Aggregate2<UC, UE, Err, Self>::update
            return from(created, create, updated, update)
        }

        inline fun <reified CC : CreationCommand, CE : CreationEvent, Err: CommandError, reified UC : UpdateCommand, UE : UpdateEvent, P, Self: AggregateWithProjection<UC, UE, Err, P, Self>> from(
            aggregateConstructor: AggregateConstructorWithProjection<CC, CE, Err, UC, UE, P, Self>,
            projection: P
        ): Configuration<CC, CE, Err, UC, UE, Aggregate2<UC, UE, Err, *>> {
            val aggregateConstructor1 = aggregateConstructor.partial(projection)
            return from<CC, CE, Err, UC, UE, Aggregate2<UC, UE, Err, *>>(aggregateConstructor1)
        }
    }
    fun rehydrated(creationEvent: CE, updateEvents: List<UE>): A = updated(created(creationEvent), updateEvents)
    fun updated(initial: A, updateEvents: List<UE>): A = updateEvents.fold(initial) { aggregate, updateEvent -> updated(aggregate, updateEvent) }
}

data class EventListener<E : Event>(val eventType: KClass<E>, val handle: (E) -> Any?) {
    companion object {
        inline fun <reified E : Event> from(noinline handle: (E) -> Any?) = EventListener(E::class, handle)
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
