package eventsourcing

@Suppress("UNCHECKED_CAST")
class CommandGateway(
    private val eventStore: EventStore,
    private val registry: List<Configuration<*, *, *, *, *, *>>
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
                        eventStore.sink(events, creationCommand.aggregateId, configuration.aggregateType(aggregate))
                        result.map { Created }
                    }
                }
            } ?: Left(NoConstructorForCommand)
        }
    }

    private fun update(updateCommand: UpdateCommand): Either<CommandError, SuccessStatus> {
        val configuration = configurationFor(updateCommand)
        return configuration?.let {
            val (creationEvent, updateEvents) = eventStore.eventsFor(updateCommand.aggregateId)
            val aggregate = configuration.rehydrated(creationEvent, updateEvents)
            val result = configuration.update(aggregate, updateCommand)
            when (result) {
                is Left -> result
                is Right -> {
                    val events = result.value
                    val updated = configuration.updated(aggregate, events)
                    eventStore.sink(events, updateCommand.aggregateId, configuration.aggregateType(updated))
                    result.map { Updated }
                }
            }
        } ?: Left(NoConstructorForCommand)
    }

    private fun configurationFor(command: Command) =
        registry.find { it.creationCommandClass.isInstance(command) || it.updateCommandClass.isInstance(command) } as Configuration<CreationCommand, CreationEvent, CommandError, UpdateCommand, UpdateEvent, Aggregate>?
}

sealed class SuccessStatus
object Created : SuccessStatus()
object Updated : SuccessStatus()

object UnrecognizedCommandType : CommandError
object NoConstructorForCommand : CommandError
object AggregateIdAlreadyTaken : CommandError
