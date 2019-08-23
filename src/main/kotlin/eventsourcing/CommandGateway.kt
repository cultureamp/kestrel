package eventsourcing

import kotlin.reflect.KClass

@Suppress("UNCHECKED_CAST")
class CommandGateway(
    private val eventStore: EventStore,
    private val aggregateConstructors: Map<KClass<out Command>, AggregateConstructor<*, *, *, *, *, *>>,
    sagas: Map<KClass<out Command>, AggregateConstructorWithProjection<*, *, *, Step, *, CommandGateway, *>>
) {
    private val sagaConstructors = sagas.mapValues { (_, value) -> value.curried(this) }.toList()

    fun dispatch(command: Command): Either<CommandError, SuccessStatus> = when (command) {
        is CreationCommand -> construct(command)
        is UpdateCommand -> update(command).map { Updated }
        else -> Left(UnrecognizedCommandType)
    }

    private fun construct(creationCommand: CreationCommand): Either<CommandError, SuccessStatus> {
        return when (eventStore.isTaken(creationCommand.aggregateId)) {
            true -> Left(AggregateIdAlreadyTaken)
            false -> {
                val sagaConstructor = sagaConstructorFor(creationCommand) as AggregateConstructor<CreationCommand, *, *, Step, *, *>?
                if (sagaConstructor != null) {
                    doSaga(sagaConstructor, creationCommand)
                } else {
                    val aggregateConstructor = aggregateConstructorFor(creationCommand) as AggregateConstructor<CreationCommand, *, *, *, *, *>?
                    if (aggregateConstructor != null) {
                        doAggregate(aggregateConstructor, creationCommand)
                    } else {
                        Left(NoConstructorForCommand)
                    }
                }
            }
        }
    }

    private fun doAggregate(aggregateConstructor: AggregateConstructor<CreationCommand, *, *, *, *, *>, creationCommand: CreationCommand): Either<CommandError, SuccessStatus> {
        val result = aggregateConstructor.create(creationCommand)
        return when (result) {
            is Left -> result
            is Right -> {
                val (creationEvent, updateEvents) = result.value
                val aggregate = (aggregateConstructor as AggregateConstructor<*, CreationEvent, *, *, *, *>).created(creationEvent)
                val updated = updated(aggregate, updateEvents)
                val events = listOf(creationEvent) + updateEvents
                eventStore.sink(updated.aggregateType(), events)
                Right(Created)
            }
        }
    }

    private fun doSaga(sagaConstructor: AggregateConstructor<CreationCommand, *, *, Step, *, *>, creationCommand: CreationCommand): Either<CommandError, SuccessStatus> {
        val result = sagaConstructor.create(creationCommand)
        return when (result) {
            is Left -> result
            is Right -> {
                val (creationEvent, updateEvents) = result.value
                val saga = (sagaConstructor as AggregateConstructor<*, CreationEvent, *, Step, *, *>).created(creationEvent) as Aggregate<Step, *, *, *>
                val updated = updated(saga, updateEvents)
                val events = listOf(creationEvent) + updateEvents
                eventStore.sink(updated.aggregateType(), events)
                Right(recursiveStep(saga)).map { Created }
            }
        }
    }

    private fun recursiveStep(saga: Aggregate<Step, *, *, *>): Either<CommandError, List<UpdateEvent>> {
        val result = saga.update(Step(saga.aggregateId))
        return when (result) {
            is Left -> result
            is Right -> when (result.value) {
                emptyList<UpdateEvent>() -> result
                else -> {
                    val updated = updated(saga, result.value) as Aggregate<Step, *, *, *>
                    eventStore.sink(updated.aggregateType(), result.value)
                    recursiveStep(updated)
                }
            }
        }
    }

    private fun update(updateCommand: UpdateCommand): Either<CommandError, List<UpdateEvent>> {
        val constructor = aggregateConstructorFor(updateCommand) as AggregateConstructor<*, CreationEvent, *, *, *, *>?
        return constructor?.let {
            val (creationEvent, updateEvents) = eventStore.eventsFor(updateCommand.aggregateId)
            val aggregate = updated(constructor.created(creationEvent), updateEvents)
            val result = (aggregate as Aggregate<UpdateCommand, *, *, *>).update(updateCommand)
            when (result) {
                is Left -> result
                is Right -> {
                    val events = result.value
                    val updated = updated(aggregate, events)
                    eventStore.sink(updated.aggregateType(), events)
                    Right(events)
                }
            }
        } ?: Left(NoConstructorForCommand)
    }

    private fun updated(initial: Aggregate<*, *, *, *>, updateEvents: List<UpdateEvent>): Aggregate<*, UpdateEvent, *, *> {
        return updateEvents.fold(initial as Aggregate<*, UpdateEvent, *, *>) { aggregate, updateEvent ->
            aggregate.updated(updateEvent) as Aggregate<*, UpdateEvent, *, *>
        }
    }

    private fun sagaConstructorFor(command: Command) = sagaConstructors.toList().find { entry -> entry.first.isInstance(command) }?.second
    private fun aggregateConstructorFor(command: Command) = aggregateConstructors.toList().find { entry -> entry.first.isInstance(command) }?.second
}

sealed class SuccessStatus
object Created : SuccessStatus()
object Updated : SuccessStatus()

object UnrecognizedCommandType : CommandError
object NoConstructorForCommand : CommandError
object AggregateIdAlreadyTaken : CommandError
