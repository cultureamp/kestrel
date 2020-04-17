package com.cultureamp.eventsourcing

import org.joda.time.DateTime
import java.util.*
import kotlin.reflect.KClass

data class Configuration<CC : CreationCommand, CE : CreationEvent, Err : CommandError, UC : UpdateCommand, UE : UpdateEvent, A : Aggregate>(
        val creationCommandClass: KClass<CC>,
        val updateCommandClass: KClass<UC>,
        val create: (CC) -> Either<Err, CE>,
        val update: A.(UC) -> Either<Err, List<UE>>,
        val created: (CE) -> A,
        val updated: A.(UE) -> A,
        val aggregateType: A.() -> String
) {
    object Builder {
        inline fun <reified CC : CreationCommand, CE : CreationEvent, Err : CommandError>create(noinline create: (CC) -> Either<Err, CE>): CreateBuilder<CC, CE, Err> {
            return CreateBuilder(CC::class, create)
        }
    }

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

        inline fun <reified CC : CreationCommand, CE : CreationEvent, Err : CommandError, reified UC : UpdateCommand, UE : UpdateEvent, reified A : Aggregate> fromStateless(
                noinline create: (CC) -> Either<Err, CE>,
                noinline update: (UC) -> Either<Err, List<UE>>,
                instance: A,
                noinline aggregateType: A.() -> String = { this::class.simpleName!! }
        ): Configuration<CC, CE, Err, UC, UE, A> {
            return from(create, { update(it) }, { instance }, { instance }, aggregateType)
        }

        inline fun <reified CC : CreationCommand, CE : CreationEvent, Err : CommandError, reified UC : UpdateCommand, UE : UpdateEvent, reified Self : TypedAggregate<UC, UE, Err, Self>> fromTypedAggregate(
                aggregateConstructor: AggregateConstructor<CC, CE, Err, UC, UE, Self>
        ): Configuration<CC, CE, Err, UC, UE, Self> {
            val created = aggregateConstructor::created
            val create = aggregateConstructor::create
            val updated = TypedAggregate<UC, UE, Err, Self>::updated
            val update = TypedAggregate<UC, UE, Err, Self>::update
            val aggregateType = TypedAggregate<UC, UE, Err, Self>::aggregateType
            return from(create, update, created, updated, aggregateType)
        }

        inline fun <reified CC : CreationCommand, CE : CreationEvent, Err : CommandError, reified UC : UpdateCommand, UE : UpdateEvent, P, Self : AggregateWithProjection<UC, UE, Err, P, Self>> fromTypedAggregate(
                aggregateConstructor: AggregateConstructorWithProjection<CC, CE, Err, UC, UE, P, Self>,
                projection: P
        ): Configuration<CC, CE, Err, UC, UE, TypedAggregate<UC, UE, Err, *>> {
            return fromTypedAggregate<CC, CE, Err, UC, UE, TypedAggregate<UC, UE, Err, *>>(aggregateConstructor.partial(projection))
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


class CreateBuilder<CC : CreationCommand, CE : CreationEvent, Err : CommandError>(
        val creationCommandClass: KClass<CC>,
        val create: (CC) -> Either<Err, CE>
) {
    inline fun <A : Aggregate, reified UC : UpdateCommand, UE : UpdateEvent> update(noinline update: A.(UC) -> Either<Err, List<UE>>) = UpdateBuilder(creationCommandClass, UC::class, create, update)
}

class UpdateBuilder<CC : CreationCommand, CE : CreationEvent, Err : CommandError, UC : UpdateCommand, UE : UpdateEvent, A : Aggregate>(
        private val creationCommandClass: KClass<CC>,
        private val updateCommandClass: KClass<UC>,
        private val create: (CC) -> Either<Err, CE>,
        private val update: A.(UC) -> Either<Err, List<UE>>
) {
    fun created(created: (CE) -> A) = CreatedBuilder(creationCommandClass, updateCommandClass, create, update, created)
    fun buildStateless(instance: A, aggregateType: A.() -> String = { this::class.simpleName!! }) = UpdatedBuilder(creationCommandClass, updateCommandClass, create, update, { instance }, { instance }).build(aggregateType)
}

class CreatedBuilder<CC : CreationCommand, CE : CreationEvent, Err : CommandError, UC : UpdateCommand, UE : UpdateEvent, A : Aggregate>(
        private val creationCommandClass: KClass<CC>,
        private val updateCommandClass: KClass<UC>,
        private val create: (CC) -> Either<Err, CE>,
        private val update: A.(UC) -> Either<Err, List<UE>>,
        private val created: (CE) -> A) {
    fun updated(updated: A.(UE) -> A) = UpdatedBuilder(creationCommandClass, updateCommandClass, create, update, created, updated)
    fun build() = UpdatedBuilder(creationCommandClass, updateCommandClass, create, update, created, { _ -> this }).build()
}

class UpdatedBuilder<CC : CreationCommand, CE : CreationEvent, Err : CommandError, UC : UpdateCommand, UE : UpdateEvent, A : Aggregate>(
        private val creationCommandClass: KClass<CC>,
        private val updateCommandClass: KClass<UC>,
        private val create: (CC) -> Either<Err, CE>,
        private val update: A.(UC) -> Either<Err, List<UE>>,
        private val created: (CE) -> A,
        private val updated: A.(UE) -> A
) {
    fun build(aggregateType: A.() -> String = { this::class.simpleName!! }) = Configuration(creationCommandClass, updateCommandClass, create, update, created, updated, aggregateType)

}