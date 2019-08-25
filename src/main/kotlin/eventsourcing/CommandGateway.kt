package eventsourcing

import kotlin.reflect.KClass

@Suppress("UNCHECKED_CAST")
class CommandGateway(
    private val eventStore: EventStore,
    private val aggregateConstructors: Map<KClass<out Command>, AggregateConstructor<*, *, *, *, *>>,
    sagas: Map<KClass<out Command>, AggregateConstructorWithProjection<*, *, Step, *, CommandGateway, *>>
) {
    private val sagaConstructors = sagas.mapValues { (_, value) -> value.curried(this) }.toList()

    fun dispatch(command: Command): Either<CommandError, SuccessStatus> = when (command) {
        is CreationCommand -> construct(command)
        is UpdateCommand -> update(command)
        else -> Left(UnrecognizedCommandType)
    }

    private fun construct(creationCommand: CreationCommand): Either<CommandError, SuccessStatus> {
        return when (eventStore.isTaken(creationCommand.aggregateId)) {
            true -> Left(AggregateIdAlreadyTaken)
            false -> {
                val sagaConstructor = sagaConstructorFor(creationCommand) as AggregateConstructor<CreationCommand, *, *, *, *>?
                val aggregateConstructor = aggregateConstructorFor(creationCommand) as AggregateConstructor<CreationCommand, *, *, *, *>?
                when {
                    sagaConstructor != null -> construct(sagaConstructor, creationCommand, ::step)
                    aggregateConstructor != null -> construct(aggregateConstructor, creationCommand)
                    else -> Left(NoConstructorForCommand)
                }
            }
        }
    }

    private fun construct(aggregateConstructor: AggregateConstructor<CreationCommand, *, *, *, *>, creationCommand: CreationCommand, stepfn: (Aggregate<Step, *, *>) -> Either<CommandError, List<UpdateEvent>> = { Right(emptyList())}): Either<CommandError, SuccessStatus> {
        val result = aggregateConstructor.create(creationCommand)
        return when (result) {
            is Left -> result
            is Right -> {
                val creationEvent = result.value
                val aggregate = (aggregateConstructor as AggregateConstructor<*, CreationEvent, *, *, *>).created(creationEvent)
                val events = listOf(creationEvent)
                eventStore.sink(aggregate.aggregateType(), events)
                Right(stepfn(aggregate as Aggregate<Step, *, *>)).map { Created }
            }
        }
    }

    private tailrec fun step(saga: Aggregate<Step, *, *>): Either<CommandError, List<UpdateEvent>> {
        val result = saga.update(Step(saga.aggregateId))
        return when (result) {
            is Left -> result
            is Right -> when (result.value) {
                emptyList<UpdateEvent>() -> result
                else -> {
                    val updated = updated(saga, result.value) as Aggregate<Step, *, *>
                    eventStore.sink(updated.aggregateType(), result.value)
                    step(updated)
                }
            }
        }
    }

    private fun update(updateCommand: UpdateCommand): Either<CommandError, SuccessStatus> {
        val constructor = aggregateConstructorFor(updateCommand) as AggregateConstructor<*, CreationEvent, *, *, *>?
        return constructor?.let {
            val (creationEvent, updateEvents) = eventStore.eventsFor(updateCommand.aggregateId)
            val aggregate = updated(constructor.created(creationEvent), updateEvents)
            val result = (aggregate as Aggregate<UpdateCommand, *, *>).update(updateCommand)
            when (result) {
                is Left -> result
                is Right -> {
                    val events = result.value
                    val updated = updated(aggregate, events)
                    eventStore.sink(updated.aggregateType(), events)
                    Right(Updated)
                }
            }
        } ?: Left(NoConstructorForCommand)
    }

    private fun updated(initial: Aggregate<*, *, *>, updateEvents: List<UpdateEvent>): Aggregate<*, UpdateEvent, *> {
        return updateEvents.fold(initial as Aggregate<*, UpdateEvent, *>) { aggregate, updateEvent ->
            aggregate.updated(updateEvent) as Aggregate<*, UpdateEvent, *>
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
