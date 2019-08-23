package eventsourcing

import kotlin.reflect.KClass

@Suppress("UNCHECKED_CAST")
class CommandGateway(
    private val eventStore: EventStore,
    aggregates: Map<KClass<out Command>, AggregateConstructor<*, *, *, *, *, *>>,
    sagas: Map<KClass<out Command>, AggregateConstructorWithProjection<*, *, *, *, *, CommandGateway, *>>
) {
    val constructors = sagas.mapValues { (_, value) -> value.curried(this) }.toList() + aggregates.toList()

    fun dispatch(command: Command): Either<CommandError, SuccessStatus> = when (command) {
        is CreationCommand -> construct(command)
        is UpdateCommand -> update(command)
        else -> Left(UnrecognizedCommandType)
    }

    private fun construct(creationCommand: CreationCommand): Either<CommandError, SuccessStatus> {
        return when (eventStore.isTaken(creationCommand.aggregateId)) {
            true -> Left(AggregateIdAlreadyTaken)
            false -> {
                val constructor = constructorFor(creationCommand) as AggregateConstructor<CreationCommand, *, *, *, *, *>?
                return constructor?.create(creationCommand)?.let { result ->
                    when (result) {
                        is Left -> result
                        is Right -> {
                            val (creationEvent, updateEvents) = result.value
                            val aggregate = (constructor as AggregateConstructor<*, CreationEvent, *, *, *, *>).created(creationEvent)
                            val updated = updated(aggregate, updateEvents)
                            val events = listOf(creationEvent) + updateEvents
                            eventStore.sink(updated.aggregateType(), events)
                            Right(Created)
                        }
                    }
                } ?: Left(NoConstructorForCommand)
            }
        }
    }

    private fun update(updateCommand: UpdateCommand): Either<CommandError, SuccessStatus> {
        val constructor = constructorFor(updateCommand) as AggregateConstructor<*, CreationEvent, *, *, *, *>?
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
                    Right(Updated)
                }
            }
        } ?: Left(NoConstructorForCommand)
    }

    private fun updated(initial: Aggregate<*, *, *, *>, updateEvents: List<UpdateEvent>): Aggregate<*, UpdateEvent, *, *> {
        return updateEvents.fold(initial as Aggregate<*, UpdateEvent, *, *>) { aggregate, updateEvent ->
            aggregate.updated(updateEvent) as Aggregate<*, UpdateEvent, *, *>
        }
    }

    private fun constructorFor(command: Command) = constructors.find { entry -> entry.first.isInstance(command) }?.second
}

sealed class SuccessStatus
object Created : SuccessStatus()
object Updated : SuccessStatus()

object UnrecognizedCommandType : CommandError
object NoConstructorForCommand : CommandError
object AggregateIdAlreadyTaken : CommandError
