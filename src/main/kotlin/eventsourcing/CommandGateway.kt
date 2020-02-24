package eventsourcing

class CommandGateway(private val eventStore: EventStore, private val registry: List<Configuration<*, *, *, *, *, *>>) {

    fun dispatch(command: Command): Either<CommandError, SuccessStatus> = when {
        command is CreationCommand && command is UpdateCommand -> createOrUpdate(command)
        command is CreationCommand -> create(command)
        command is UpdateCommand -> update(command)
        else -> Left(UnrecognizedCommandType)
    }

    private fun createOrUpdate(command: Command): Either<CommandError, SuccessStatus> {
        val events = eventStore.eventsFor(command.aggregateId)
        return when (events.isEmpty()) {
            true -> configurationFor(command)?.let { it.create(command as CreationCommand, eventStore).map { Created } } ?: Left(NoConstructorForCommand)
            false -> configurationFor(command)?.let { it.update(command as UpdateCommand, events, eventStore).map { Updated } } ?: Left(NoConstructorForCommand)
        }
    }

    private fun create(command: CreationCommand): Either<CommandError, SuccessStatus> {
        val events = eventStore.eventsFor(command.aggregateId)
        return when (events.isEmpty()) {
            true -> configurationFor(command)?.let { it.create(command, eventStore).map { Created } } ?: Left(NoConstructorForCommand)
            false -> Left(AggregateAlreadyExists)
        }
    }

    private fun update(command: UpdateCommand): Either<CommandError, SuccessStatus> {
        val events = eventStore.eventsFor(command.aggregateId)
        return when (events.isEmpty()) {
            true -> Left(AggregateNotFound)
            false -> configurationFor(command)?.let { it.update(command, events, eventStore).map { Updated } } ?: Left(NoConstructorForCommand)
        }
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
object AggregateAlreadyExists : CommandError
object AggregateNotFound : CommandError
