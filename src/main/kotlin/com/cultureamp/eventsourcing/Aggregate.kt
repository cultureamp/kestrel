package com.cultureamp.eventsourcing

import org.joda.time.DateTime
import java.util.*
import kotlin.reflect.KClass

interface SimpleAggregate<UC : UpdateCommand, UE : UpdateEvent> {
    fun updated(event: UE): SimpleAggregate<UC, UE>
    fun update(command: UC): Either<DomainError, List<UE>>
}
interface SimpleAggregateConstructor<CC : CreationCommand, CE : CreationEvent, UC : UpdateCommand, UE : UpdateEvent> {
    fun created(event: CE): SimpleAggregate<UC, UE>
    fun create(command: CC): Either<DomainError, CE>
    fun aggregateType(): String = this::class.companionClassName
}

interface SimpleAggregateWithProjection<UC : UpdateCommand, UE : UpdateEvent, P> {
    fun updated(event: UE): SimpleAggregateWithProjection<UC, UE, P>
    fun update(projection: P, command: UC): Either<DomainError, List<UE>>

    fun partial(projection: P): SimpleAggregate<UC, UE> {
        return object : SimpleAggregate<UC, UE> {
            override fun updated(event: UE): SimpleAggregate<UC, UE> {
                return this@SimpleAggregateWithProjection.updated(event).partial(projection)
            }

            override fun update(command: UC): Either<DomainError, List<UE>> {
                return update(projection, command)
            }
        }
    }
}
interface SimpleAggregateConstructorWithProjection<CC : CreationCommand, CE : CreationEvent, UC : UpdateCommand, UE : UpdateEvent, P> {
    fun created(event: CE): SimpleAggregateWithProjection<UC, UE, P>
    fun create(projection: P, command: CC): Either<DomainError, CE>
    fun aggregateType(): String = this::class.companionClassName
    fun partial(projection: P): SimpleAggregateConstructor<CC, CE, UC, UE> {
        return object : SimpleAggregateConstructor<CC, CE, UC, UE> {
            override fun created(event: CE): SimpleAggregate<UC, UE> {
                return this@SimpleAggregateConstructorWithProjection.created(event).partial(projection)
            }

            override fun create(command: CC): Either<DomainError, CE> {
                return create(projection, command)
            }

            override fun aggregateType(): String = this@SimpleAggregateConstructorWithProjection.aggregateType()
        }
    }
}

interface Aggregate<UC : UpdateCommand, UE : UpdateEvent, Err : DomainError, M : EventMetadata, out Self : Aggregate<UC, UE, Err, M, Self>> {
    fun updated(event: UE): Self
    fun update(command: UC, metadata: M): Either<Err, List<UE>>

    companion object {
        fun <UC : UpdateCommand, UE : UpdateEvent, Err : DomainError, M : EventMetadata, A : Any> from(
            aggregate: A,
            update: A.(UC, M) -> Either<Err, List<UE>>,
            updated: A.(UE) -> A = { _ -> this }
        ): Aggregate<UC, UE, Err, M, Aggregate<UC, UE, Err, M, *>> {
            return object : Aggregate<UC, UE, Err, M, Aggregate<UC, UE, Err, M, *>> {
                override fun updated(event: UE): Aggregate<UC, UE, Err, M, *> {
                    val updatedAggregate = aggregate.updated(event)
                    return from(updatedAggregate, update, updated)
                }

                override fun update(command: UC, metadata: M): Either<Err, List<UE>> {
                    return aggregate.update(command, metadata)
                }
            }
        }

        fun <UC : UpdateCommand, UE : UpdateEvent, M : EventMetadata, A : Any> from(
            aggregate: SimpleAggregate<UC, UE>,
        ): Aggregate<UC, UE, DomainError, M, Aggregate<UC, UE, DomainError, M, *>> {
            return object : Aggregate<UC, UE, DomainError, M, Aggregate<UC, UE, DomainError, M, *>> {
                override fun updated(event: UE): Aggregate<UC, UE, DomainError, M, *> {
                    val updatedAggregate = aggregate.updated(event)
                    return from<UC, UE, M, A>(updatedAggregate)
                }

                override fun update(command: UC, metadata: M): Either<DomainError, List<UE>> {
                    return aggregate.update(command)
                }
            }
        }
    }
}

interface AggregateWithProjection<UC : UpdateCommand, UE : UpdateEvent, Err : DomainError, P, M : EventMetadata, Self : AggregateWithProjection<UC, UE, Err, P, M, Self>> {
    fun updated(event: UE): Self
    fun update(projection: P, command: UC, metadata: M): Either<Err, List<UE>>

    fun partial(projection: P): Aggregate<UC, UE, Err, M, Aggregate<UC, UE, Err, M, *>> {
        return object : Aggregate<UC, UE, Err, M, Aggregate<UC, UE, Err, M, *>> {

            override fun updated(event: UE): Aggregate<UC, UE, Err, M, *> {
                return this@AggregateWithProjection.updated(event).partial(projection)
            }

            override fun update(command: UC, metadata: M): Either<Err, List<UE>> {
                return update(projection, command, metadata)
            }
        }
    }
}

interface AggregateConstructor<CC : CreationCommand, CE : CreationEvent, Err : DomainError, UC : UpdateCommand, UE : UpdateEvent, M : EventMetadata, Self : Aggregate<UC, UE, Err, M, Self>> {
    fun created(event: CE): Self
    fun create(command: CC, metadata: M): Either<Err, CE>
    fun aggregateType(): String = this::class.companionClassName

    companion object {
        inline fun <CC : CreationCommand, CE : CreationEvent, Err : DomainError, UC : UpdateCommand, UE : UpdateEvent, M : EventMetadata, reified A : Any> from(
            noinline create: (CC, M) -> Either<Err, CE>,
            noinline update: A.(UC, M) -> Either<Err, List<UE>>,
            noinline created: (CE) -> A,
            noinline updated: A.(UE) -> A = { _ -> this },
            noinline aggregateType: () -> String = { A::class.simpleName!! }
        ): AggregateConstructor<CC, CE, Err, UC, UE, M, Aggregate<UC, UE, Err, M, *>> {
            return object : AggregateConstructor<CC, CE, Err, UC, UE, M, Aggregate<UC, UE, Err, M, *>> {
                override fun created(event: CE): Aggregate<UC, UE, Err, M, *> {
                    val createdAggregate = created(event)
                    return Aggregate.from(createdAggregate, update, updated)
                }

                override fun create(command: CC, metadata: M): Either<Err, CE> = create(command, metadata)

                override fun aggregateType() = aggregateType()
            }
        }

        inline fun <CC : CreationCommand, CE : CreationEvent, UC : UpdateCommand, UE : UpdateEvent, M : EventMetadata, reified A : Any> from(
            simpleAggregateConstructor: SimpleAggregateConstructor<CC, CE, UC, UE>
        ): AggregateConstructor<CC, CE, DomainError, UC, UE, M, Aggregate<UC, UE, DomainError, M, *>> {
            return object : AggregateConstructor<CC, CE, DomainError, UC, UE, M, Aggregate<UC, UE, DomainError, M, *>> {
                override fun created(event: CE): Aggregate<UC, UE, DomainError, M, *> {
                    val createdAggregate = simpleAggregateConstructor.created(event)
                    return Aggregate.from<UC, UE, M, SimpleAggregate<UC, UE>>(createdAggregate)
                }

                override fun create(command: CC, metadata: M): Either<DomainError, CE> = simpleAggregateConstructor.create(command)

                override fun aggregateType() = simpleAggregateConstructor.aggregateType()
            }
        }

        inline fun <CC : CreationCommand, CE : CreationEvent, Err : DomainError, UC : UpdateCommand, UE : UpdateEvent, M : EventMetadata, reified A : Any> fromStateless(
            noinline create: (CC, M) -> Either<Err, CE>,
            noinline update: (UC, M) -> Either<Err, List<UE>>,
            instance: A,
            noinline aggregateType: () -> String = { A::class.simpleName!! }
        ): AggregateConstructor<CC, CE, Err, UC, UE, M, Aggregate<UC, UE, Err, M, *>> {
            return from(create, { uc: UC, m: M -> update(uc, m) }, { instance }, { instance }, aggregateType)
        }
    }
}

interface AggregateConstructorWithProjection<CC : CreationCommand, CE : CreationEvent, Err : DomainError, UC : UpdateCommand, UE : UpdateEvent, P, M : EventMetadata, Self : AggregateWithProjection<UC, UE, Err, P, M, Self>> {
    fun created(event: CE): Self
    fun create(projection: P, command: CC, metadata: M): Either<Err, CE>
    fun aggregateType(): String = this::class.companionClassName
    fun partial(projection: P): AggregateConstructor<CC, CE, Err, UC, UE, M, Aggregate<UC, UE, Err, M, *>> {
        return object : AggregateConstructor<CC, CE, Err, UC, UE, M, Aggregate<UC, UE, Err, M, *>> {
            override fun created(event: CE): Aggregate<UC, UE, Err, M, *> {
                return this@AggregateConstructorWithProjection.created(event).partial(projection)
            }

            override fun create(command: CC, metadata: M): Either<Err, CE> {
                return create(projection, command, metadata)
            }

            override fun aggregateType(): String = this@AggregateConstructorWithProjection.aggregateType()
        }
    }
}

internal fun <CC : CreationCommand, CE : CreationEvent, Err : DomainError, UC : UpdateCommand, UE : UpdateEvent, M : EventMetadata>
AggregateConstructor<CC, CE, Err, UC, UE, M, Aggregate<UC, UE, Err, M, *>>.create(
    creationCommand: CC,
    metadata: M,
    eventStore: EventStore<M>
): Either<CommandError, Unit> {
    return create(creationCommand, metadata).map { domainEvent ->
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

internal fun <CC : CreationCommand, CE : CreationEvent, Err : DomainError, UC : UpdateCommand, UE : UpdateEvent, M : EventMetadata>
AggregateConstructor<CC, CE, Err, UC, UE, M, Aggregate<UC, UE, Err, M, *>>.update(
    updateCommand: UC,
    metadata: M,
    events: List<Event<M>>,
    eventStore: EventStore<M>
): Either<CommandError, Unit> {
    val aggregate = rehydrate(events)
    return aggregate.flatMap {
        it.update(updateCommand, metadata).flatMap { domainEvents ->
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
private fun <CC : CreationCommand, CE : CreationEvent, Err : DomainError, M : EventMetadata, UC : UpdateCommand, UE : UpdateEvent>
AggregateConstructor<CC, CE, Err, UC, UE, M, Aggregate<UC, UE, Err, M, *>>.rehydrate(
    events: List<Event<M>>
): Either<ConstructorTypeMismatch, Aggregate<UC, UE, Err, M, *>> {
    val creationEvent = events.first()
    val creationDomainEvent = creationEvent.domainEvent as CE
    val aggregate = if (creationEvent.aggregateType == aggregateType()) {
        Right(created(creationDomainEvent))
    } else {
        Left(ConstructorTypeMismatch(aggregateType(), creationDomainEvent::class))
    }
    val updateEvents = events.drop(1).map { it.domainEvent as UE }
    return aggregate.map { it.updated(updateEvents) }
}

@Suppress("UNCHECKED_CAST")
private fun <UC : UpdateCommand, UE : UpdateEvent, Err : DomainError, M : EventMetadata> Aggregate<UC, UE, Err, M, *>.updated(updateEvents: List<UE>): Aggregate<UC, UE, Err, M, *> {
    return updateEvents.fold(this) { aggregate, updateEvent -> aggregate.updated(updateEvent) as Aggregate<UC, UE, Err, M, *> }
}

val <T : Any> KClass<T>.companionClassName get() = if (isCompanion) this.java.declaringClass.simpleName!! else this::class.simpleName!!

data class ConstructorTypeMismatch(val aggregateType: String, val eventType: KClass<out CreationEvent>) : CommandError
