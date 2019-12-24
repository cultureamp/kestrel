package eventsourcing

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
            false -> configurationFor(creationCommand)?.let { it.create(creationCommand, eventStore).map { Created } } ?: Left(NoConstructorForCommand)
        }
    }

    private fun update(updateCommand: UpdateCommand): Either<CommandError, SuccessStatus> {
        return configurationFor(updateCommand)?.let { it.update(updateCommand, eventStore).map { Created } } ?: Left(NoConstructorForCommand)
    }

    @Suppress("UNCHECKED_CAST")
    private fun configurationFor(command: Command) =
        registry.find { it.creationCommandClass.isInstance(command) || it.updateCommandClass.isInstance(command) } as Configuration<CreationCommand, *, *, UpdateCommand, *, *>?
}

sealed class SuccessStatus
object Created : SuccessStatus()
object Updated : SuccessStatus()

object UnrecognizedCommandType : CommandError
object NoConstructorForCommand : CommandError
object AggregateIdAlreadyTaken : CommandError
