package com.cultureamp.eventsourcing

import org.joda.time.DateTime
import java.util.*
import kotlin.reflect.KClass

interface SimpleAggregate<UC: UpdateCommand, UE: UpdateEvent> : Aggregate<UC, UE, DomainError, SimpleAggregate<UC, UE>>
interface SimpleAggregateConstructor<CC: CreationCommand, CE: CreationEvent, UC: UpdateCommand, UE: UpdateEvent> : AggregateConstructor<CC, CE, DomainError, UC, UE, SimpleAggregate<UC, UE>>

interface SimpleAggregateWithProjection<UC: UpdateCommand, UE: UpdateEvent, P> : AggregateWithProjection<UC, UE, DomainError, P, SimpleAggregateWithProjection<UC, UE, P>>
interface SimpleAggregateConstructorWithProjection<CC: CreationCommand, CE: CreationEvent, UC: UpdateCommand, UE: UpdateEvent, P> : AggregateConstructorWithProjection<CC, CE, DomainError, UC, UE, P, SimpleAggregateWithProjection<UC, UE, P>>


interface Aggregate<UC: UpdateCommand, UE: UpdateEvent, Err: DomainError, out Self : Aggregate<UC, UE, Err, Self>> {
    fun updated(event: UE): Self
    fun update(command: UC): Either<Err, List<UE>>

    companion object {
        fun <UC : UpdateCommand, UE : UpdateEvent, Err: DomainError, A : Any> from(
            aggregate: A,
            update: A.(UC) -> Either<Err, List<UE>>,
            updated: A.(UE) -> A = { _ -> this }
        ): Aggregate<UC, UE, Err, Aggregate<UC, UE, Err, *>> {
            return object : Aggregate<UC, UE, Err, Aggregate<UC, UE, Err, *>> {
                override fun updated(event: UE): Aggregate<UC, UE, Err, *> {
                    val updatedAggregate = aggregate.updated(event)
                    return from(updatedAggregate, update, updated)
                }

                override fun update(command: UC): Either<Err, List<UE>> {
                    return aggregate.update(command)
                }
            }
        }
    }
}

interface AggregateWithProjection<UC: UpdateCommand, UE: UpdateEvent, Err: DomainError, P, Self : AggregateWithProjection<UC, UE, Err, P, Self>> {
    fun updated(event: UE): Self
    fun update(projection: P, command: UC): Either<Err, List<UE>>

    fun partial(projection: P): Aggregate<UC, UE, Err, Aggregate<UC, UE, Err, *>> {
        return object:Aggregate<UC, UE, Err, Aggregate<UC, UE, Err, *>> {

            override fun updated(event: UE): Aggregate<UC, UE, Err, *> {
                return this@AggregateWithProjection.updated(event).partial(projection)
            }

            override fun update(command: UC): Either<Err, List<UE>> {
                return update(projection, command)
            }
        }
    }
}

interface AggregateConstructor<CC: CreationCommand, CE: CreationEvent, Err: DomainError, UC: UpdateCommand, UE: UpdateEvent, Self: Aggregate<UC, UE, Err, Self>> {
    fun created(event: CE): Self
    fun create(command: CC): Either<Err, CE>
    fun aggregateType(): String = this::class.companionClassName

    companion object {
        inline fun <CC: CreationCommand, CE: CreationEvent, Err: DomainError, UC: UpdateCommand, UE: UpdateEvent, reified A: Any> from(
            noinline create: (CC) -> Either<Err, CE>,
            noinline update: A.(UC) -> Either<Err, List<UE>>,
            noinline created: (CE) -> A,
            noinline updated: A.(UE) -> A = { _ -> this },
            noinline aggregateType: () -> String = { A::class.simpleName!! }
        ): AggregateConstructor<CC, CE, Err, UC, UE, Aggregate<UC, UE, Err, *>> {
            return object : AggregateConstructor<CC, CE, Err, UC, UE, Aggregate<UC, UE, Err, *>> {
                override fun created(event: CE): Aggregate<UC, UE, Err, *> {
                    val createdAggregate = created(event)
                    return Aggregate.from(createdAggregate, update, updated)
                }

                override fun create(command: CC): Either<Err, CE> = create(command)

                override fun aggregateType() = aggregateType()
            }
        }

        inline fun <CC : CreationCommand, CE : CreationEvent, Err : DomainError, UC : UpdateCommand, UE : UpdateEvent, reified A : Any> fromStateless(
            noinline create: (CC) -> Either<Err, CE>,
            noinline update: (UC) -> Either<Err, List<UE>>,
            instance: A,
            noinline aggregateType: () -> String = { A::class.simpleName!! }
        ): AggregateConstructor<CC, CE, Err, UC, UE, Aggregate<UC, UE, Err, *>> {
            return from(create, { update(it) }, { instance }, { instance }, aggregateType)
        }
    }
}

interface AggregateConstructorWithProjection<CC: CreationCommand, CE: CreationEvent, Err: DomainError, UC: UpdateCommand, UE: UpdateEvent, P, Self : AggregateWithProjection<UC, UE, Err, P, Self>> {
    fun created(event: CE): Self
    fun create(projection: P, command: CC): Either<Err, CE>
    fun aggregateType(): String = this::class.companionClassName
    fun partial(projection: P): AggregateConstructor<CC, CE, Err, UC, UE, Aggregate<UC, UE, Err, *>> {
        return object:AggregateConstructor<CC, CE, Err, UC, UE, Aggregate<UC, UE, Err, *>> {
            override fun created(event: CE): Aggregate<UC, UE, Err, *> {
                return this@AggregateConstructorWithProjection.created(event).partial(projection)
            }

            override fun create(command: CC): Either<Err, CE> {
                return create(projection, command)
            }

            override fun aggregateType(): String = this@AggregateConstructorWithProjection.aggregateType()
        }
    }
}

internal fun <CC: CreationCommand, CE: CreationEvent, Err: DomainError, UC: UpdateCommand, UE: UpdateEvent, M : EventMetadata> AggregateConstructor<CC, CE, Err, UC, UE, Aggregate<UC, UE, Err, *>>.create(
    creationCommand: CC,
    metadata: M,
    eventStore: EventStore<M>
): Either<CommandError, Unit> {
    return create(creationCommand).map { domainEvent ->
        created(domainEvent) // called to ensure the create event handler doesn't throw any exceptions
        val event = Event(
            id = UUID.randomUUID(),
            aggregateId = creationCommand.aggregateId,
            aggregateSequence = 1,
            aggregateType = aggregateType(),
            createdAt = DateTime.now(),
            metadata = metadata,
            domainEvent = domainEvent
        )
        eventStore.sink(listOf(event), creationCommand.aggregateId)
    }.flatten()
}

internal fun <CC: CreationCommand, CE: CreationEvent, Err: DomainError, UC: UpdateCommand, UE: UpdateEvent, M : EventMetadata> AggregateConstructor<CC, CE, Err, UC, UE, Aggregate<UC, UE, Err, *>>.update(
    updateCommand: UC,
    metadata: M,
    events: List<Event<M>>,
    eventStore: EventStore<M>
): Either<CommandError, Unit> {
    val aggregate = rehydrate(events)
    return aggregate.flatMap {
        it.update(updateCommand).flatMap { domainEvents ->
            it.updated(domainEvents) // called to ensure the update event handler doesn't throw any exceptions
            val offset = events.last().aggregateSequence + 1
            val createdAt = DateTime()
            val storableEvents = domainEvents.withIndex().map { (index, domainEvent) ->
                Event(
                    id = UUID.randomUUID(),
                    aggregateId = updateCommand.aggregateId,
                    aggregateSequence = offset + index,
                    aggregateType = aggregateType(),
                    createdAt = createdAt,
                    metadata = metadata,
                    domainEvent = domainEvent
                )
            }
            eventStore.sink(storableEvents, updateCommand.aggregateId)
        }
    }
}

@Suppress("UNCHECKED_CAST")
private fun <CC: CreationCommand, CE: CreationEvent, Err: DomainError, M: EventMetadata, UC: UpdateCommand, UE: UpdateEvent> AggregateConstructor<CC, CE, Err, UC, UE, Aggregate<UC, UE, Err, *>>.rehydrate(events: List<Event<M>>): Either<WrongAggregateConstructorForEvent, Aggregate<UC, UE, Err, *>> {
    val creationEvent = events.first()
    val creationDomainEvent = creationEvent.domainEvent as CE
    val aggregate = if (creationEvent.aggregateType == aggregateType()) {
        Right(created(creationDomainEvent))
    } else {
        Left(WrongAggregateConstructorForEvent(aggregateType(), creationDomainEvent::class))
    }
    val updateEvents = events.drop(1).map { it.domainEvent as UE }
    return aggregate.map { it.updated(updateEvents) }
}

@Suppress("UNCHECKED_CAST")
private fun <UC: UpdateCommand, UE: UpdateEvent, Err: DomainError> Aggregate<UC, UE, Err, *>.updated(updateEvents: List<UE>): Aggregate<UC, UE, Err, *> {
    return updateEvents.fold(this) { aggregate, updateEvent -> aggregate.updated(updateEvent) as Aggregate<UC, UE, Err, *> }
}

val <T : Any> KClass<T>.companionClassName get() = if (isCompanion) this.java.declaringClass.simpleName!! else this::class.simpleName!!

data class WrongAggregateConstructorForEvent(val aggregateType: String, val eventType: KClass<out CreationEvent>) : CommandError
