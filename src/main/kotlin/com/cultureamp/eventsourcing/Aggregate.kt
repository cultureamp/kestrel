package com.cultureamp.eventsourcing

import org.joda.time.DateTime
import java.util.*

interface BaseAggregate {
    fun aggregateType(): String = this::class.simpleName!!
}

interface SimpleAggregate<UC: UpdateCommand<DomainError>, UE: UpdateEvent> : Aggregate<UC, UE, DomainError, SimpleAggregate<UC, UE>>
interface SimpleAggregateConstructor<CC: CreationCommand<DomainError>, CE: CreationEvent, UC: UpdateCommand<DomainError>, UE: UpdateEvent> : AggregateConstructor<CC, CE, DomainError, UC, UE, SimpleAggregate<UC, UE>>

interface SimpleAggregateWithProjection<UC: UpdateCommand<DomainError>, UE: UpdateEvent, P> : AggregateWithProjection<UC, UE, DomainError, P, SimpleAggregateWithProjection<UC, UE, P>>
interface SimpleAggregateConstructorWithProjection<CC: CreationCommand<DomainError>, CE: CreationEvent, UC: UpdateCommand<DomainError>, UE: UpdateEvent, P> : AggregateConstructorWithProjection<CC, CE, DomainError, UC, UE, P, SimpleAggregateWithProjection<UC, UE, P>>


interface Aggregate<UC: UpdateCommand<Err>, UE: UpdateEvent, Err: DomainError, out Self : Aggregate<UC, UE, Err, Self>> : BaseAggregate {
    fun updated(event: UE): Self
    fun update(command: UC): Result<Err, List<UE>>

    companion object {
        fun <UC : UpdateCommand<Err>, UE : UpdateEvent, Err: DomainError, A : Any> from(
            aggregate: A,
            update: A.(UC) -> Result<Err, List<UE>>,
            updated: A.(UE) -> A = { _ -> this },
            aggregateType: (A.() -> String)
        ): Aggregate<UC, UE, Err, Aggregate<UC, UE, Err, *>> {
            return object : Aggregate<UC, UE, Err, Aggregate<UC, UE, Err, *>> {
                override fun updated(event: UE): Aggregate<UC, UE, Err, *> {
                    val updatedAggregate = aggregate.updated(event)
                    return from(updatedAggregate, update, updated, aggregateType)
                }

                override fun update(command: UC): Result<Err, List<UE>> {
                    return aggregate.update(command)
                }

                override fun aggregateType(): String {
                    return aggregateType.invoke(aggregate)
                }
            }
        }
    }
}

interface AggregateWithProjection<UC: UpdateCommand<Err>, UE: UpdateEvent, Err: DomainError, P, Self : AggregateWithProjection<UC, UE, Err, P, Self>>: BaseAggregate {
    fun updated(event: UE): Self
    fun update(projection: P, command: UC): Result<Err, List<UE>>

    fun partial(projection: P): Aggregate<UC, UE, Err, Aggregate<UC, UE, Err, *>> {
        return object:Aggregate<UC, UE, Err, Aggregate<UC, UE, Err, *>> {

            override fun updated(event: UE): Aggregate<UC, UE, Err, *> {
                return this@AggregateWithProjection.updated(event).partial(projection)
            }

            override fun update(command: UC): Result<Err, List<UE>> {
                return update(projection, command)
            }

            override fun aggregateType(): String = this@AggregateWithProjection.aggregateType()
        }
    }
}

interface AggregateConstructor<CC: CreationCommand<Err>, CE: CreationEvent, Err: DomainError, UC: UpdateCommand<Err>, UE: UpdateEvent, Self: Aggregate<UC, UE, Err, Self>> {
    fun created(event: CE): Self
    fun create(command: CC): Result<Err, CE>

    companion object {
        inline fun <CC: CreationCommand<Err>, CE: CreationEvent, Err: DomainError, UC: UpdateCommand<Err>, UE: UpdateEvent, reified A: Any> from(
            noinline create: (CC) -> Result<Err, CE>,
            noinline update: A.(UC) -> Result<Err, List<UE>>,
            noinline created: (CE) -> A,
            noinline updated: A.(UE) -> A = { _ -> this },
            noinline aggregateType: (A.() -> String) = { A::class.simpleName!! }
        ): AggregateConstructor<CC, CE, Err, UC, UE, Aggregate<UC, UE, Err, *>> {
            return object : AggregateConstructor<CC, CE, Err, UC, UE, Aggregate<UC, UE, Err, *>> {
                override fun created(event: CE): Aggregate<UC, UE, Err, *> {
                    val createdAggregate = created(event)
                    return Aggregate.from(createdAggregate, update, updated, aggregateType)
                }

                override fun create(command: CC): Result<Err, CE> = create(command)
            }
        }

        inline fun <CC : CreationCommand<Err>, CE : CreationEvent, Err : DomainError, UC : UpdateCommand<Err>, UE : UpdateEvent, reified A : Any> fromStateless(
            noinline create: (CC) -> Result<Err, CE>,
            noinline update: (UC) -> Result<Err, List<UE>>,
            instance: A,
            noinline aggregateType: (A.() -> String) = { A::class.simpleName!! }
        ): AggregateConstructor<CC, CE, Err, UC, UE, Aggregate<UC, UE, Err, *>> {
            return from(create, { update(it) }, { instance }, { instance }, aggregateType)
        }
    }
}

interface AggregateConstructorWithProjection<CC: CreationCommand<Err>, CE: CreationEvent, Err: DomainError, UC: UpdateCommand<Err>, UE: UpdateEvent, P, Self : AggregateWithProjection<UC, UE, Err, P, Self>> {
    fun created(event: CE): Self
    fun create(projection: P, command: CC): Result<Err, CE>
    fun partial(projection: P): AggregateConstructor<CC, CE, Err, UC, UE, Aggregate<UC, UE, Err, *>> {
        return object:AggregateConstructor<CC, CE, Err, UC, UE, Aggregate<UC, UE, Err, *>> {
            override fun created(event: CE): Aggregate<UC, UE, Err, *> {
                return this@AggregateConstructorWithProjection.created(event).partial(projection)
            }

            override fun create(command: CC): Result<Err, CE> {
                return create(projection, command)
            }
        }
    }
}

internal fun <CC: CreationCommand<Err>, CE: CreationEvent, Err: DomainError, UC: UpdateCommand<Err>, UE: UpdateEvent, M : EventMetadata> AggregateConstructor<CC, CE, Err, UC, UE, Aggregate<UC, UE, Err, *>>.create(
    creationCommand: CC,
    metadata: M,
    eventStore: EventStore<M>
): Result<Either<ConcurrencyError, Err>, Unit> {
    val creationResult = create(creationCommand)
    return when (creationResult) {
        is Failure -> Failure(Right(creationResult.error))
        is Success -> {
            val domainEvent = creationResult.value
            val aggregate = created(domainEvent)
            val event = Event(
                id = UUID.randomUUID(),
                aggregateId = creationCommand.aggregateId,
                aggregateSequence = 1,
                createdAt = DateTime.now(),
                metadata = metadata,
                domainEvent = domainEvent
            )
            val sinkResult = eventStore.sink(listOf(event), creationCommand.aggregateId, aggregate.aggregateType())
            when (sinkResult) {
                is Failure -> Failure(Left(sinkResult.error))
                is Success -> Success(sinkResult.value)
            }
        }
    }
}

@Suppress("UNCHECKED_CAST")
internal fun <CC: CreationCommand<Err>, CE: CreationEvent, Err: DomainError, UC: UpdateCommand<Err>, UE: UpdateEvent, M : EventMetadata> AggregateConstructor<CC, CE, Err, UC, UE, Aggregate<UC, UE, Err, *>>.update(
    updateCommand: UC,
    metadata: M,
    events: List<Event<M>>,
    eventStore: EventStore<M>
): Result<Either<ConcurrencyError, Err>, Unit> {
    val creationEvent = events.first().domainEvent as CreationEvent
    val updateEvents = events.slice(1 until events.size).map { it.domainEvent as UpdateEvent }
    val aggregate = rehydrated(creationEvent as CE, updateEvents as List<UE>)
    val updateResult = aggregate.update(updateCommand)
    return when (updateResult) {
        is Failure -> Failure(Right(updateResult.error))
        is Success -> {
            val domainEvents = updateResult.value
            val updated = aggregate.updated(domainEvents)
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
            val sinkResult = eventStore.sink(storableEvents, updateCommand.aggregateId, updated.aggregateType())
            when (sinkResult) {
                is Failure -> Failure(Left(sinkResult.error))
                is Success -> Success(sinkResult.value)
            }
        }
    }
}

internal fun <CC: CreationCommand<Err>, CE: CreationEvent, Err: DomainError, UC: UpdateCommand<Err>, UE: UpdateEvent> AggregateConstructor<CC, CE, Err, UC, UE, Aggregate<UC, UE, Err, *>>.rehydrated(
    creationEvent: CE,
    updateEvents: List<UE>
): Aggregate<UC, UE, Err, *> {
    return created(creationEvent).updated(updateEvents)
}

@Suppress("UNCHECKED_CAST")
private fun <UC: UpdateCommand<Err>, UE: UpdateEvent, Err: DomainError> Aggregate<UC, UE, Err, *>.updated(updateEvents: List<UE>): Aggregate<UC, UE, Err, *> {
    return updateEvents.fold(this) { aggregate, updateEvent -> aggregate.updated(updateEvent) as Aggregate<UC, UE, Err, *> }
}