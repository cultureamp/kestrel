package eventsourcing

import kotlin.reflect.KClass

@Suppress("UNCHECKED_CAST")
class CommandGateway(
    private val eventStore: EventStore,
    private val configurations: Map<KClass<out Command>, Configuration<*, *, *, *, *>>
) {

    fun dispatch(command: Command): Either<CommandError, SuccessStatus> = when (command) {
        is CreationCommand -> construct(command)
        is UpdateCommand -> update(command)
        else -> Left(UnrecognizedCommandType)
    }

    private fun construct(creationCommand: CreationCommand): Either<CommandError, SuccessStatus> {
        return when (eventStore.isTaken(creationCommand.aggregateId)) {
            true -> Left(AggregateIdAlreadyTaken)
            false -> configurationFor(creationCommand)?.let { configuration ->
                val result = configuration.create(creationCommand)
                when (result) {
                    is Left -> result
                    is Right -> {
                        val creationEvent = result.value
                        val aggregate = configuration.created(creationEvent)
                        val events = listOf(creationEvent)
                        eventStore.sink(aggregate.aggregateType(), events)
                        Right(step(aggregate, configuration)).map { Created }
                    }
                }
            } ?: Left(NoConstructorForCommand)
        }
    }

    private fun update(updateCommand: UpdateCommand): Either<CommandError, SuccessStatus> {
        val configuration = configurationFor(updateCommand)
        return configuration?.let {
            val (creationEvent, updateEvents) = eventStore.eventsFor(updateCommand.aggregateId)
            val initial = configuration.created(creationEvent)
            val aggregate = updated(initial, configuration, updateEvents)
            val result = configuration.update(aggregate, updateCommand)
            when (result) {
                is Left -> result
                is Right -> {
                    val events = result.value
                    val updated = updated(aggregate, configuration, events)
                    eventStore.sink(updated.aggregateType(), events)
                    Right(step(aggregate, configuration)).map { Updated }
                }
            }
        } ?: Left(NoConstructorForCommand)
    }

    private fun updated(initial: Aggregate, configuration: Configuration<*,*,*, UpdateEvent, Aggregate>, updateEvents: List<UpdateEvent>): Aggregate {
        return updateEvents.fold(initial) { aggregate, updateEvent ->
            configuration.updated(aggregate, updateEvent)
        }
    }

    private tailrec fun step(saga: Aggregate, configuration: Configuration<CreationCommand, CreationEvent, UpdateCommand, UpdateEvent, Aggregate>): Either<CommandError, List<UpdateEvent>> {
        val result = configuration.step(saga, this)
        return when (result) {
            is Left -> result
            is Right -> when (result.value) {
                emptyList<UpdateEvent>() -> result
                else -> {
                    val updated = updated(saga, configuration, result.value)
                    eventStore.sink(updated.aggregateType(), result.value)
                    step(updated, configuration)
                }
            }
        }
    }

    private fun configurationFor(command: Command) =
        configurations.toList().find { entry -> entry.first.isInstance(command) }?.second as Configuration<CreationCommand, CreationEvent, UpdateCommand, UpdateEvent, Aggregate>?
}

sealed class SuccessStatus
object Created : SuccessStatus()
object Updated : SuccessStatus()

object UnrecognizedCommandType : CommandError
object NoConstructorForCommand : CommandError
object AggregateIdAlreadyTaken : CommandError
