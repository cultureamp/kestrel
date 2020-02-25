package eventsourcing

class CommandGateway(private val eventStore: EventStore, private val registry: List<Configuration<*, *, *, *, *, *>>) {

    tailrec fun dispatch(command: Command, retries: Int = 5): Either<CommandError, SuccessStatus> {
        val result = createOrUpdate(command)
        return if (result is Left && result.error is RetriableError && retries > 0) {
            Thread.sleep(500L) // TODO this should use coroutine delay and dispatch shouldbe suspended function
            dispatch(command, retries - 1)
        } else {
            result
        }
    }

    private fun createOrUpdate(command: Command): Either<CommandError, SuccessStatus> {
        val configuration = configurationFor(command) ?: return Left(NoConstructorForCommand)
        val events = eventStore.eventsFor(command.aggregateId)
        return if (events.isEmpty()) when (command) {
            is CreationCommand -> configuration.create(command, eventStore).map { Created }
            else -> Left(AggregateNotFound)
        } else when (command) {
            is UpdateCommand -> configuration.update(command, events, eventStore).map { Updated }
            else -> Left(AggregateAlreadyExists)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun configurationFor(command: Command) =
        registry.find { it.creationCommandClass.isInstance(command) || it.updateCommandClass.isInstance(command) } as Configuration<CreationCommand, *, *, UpdateCommand, *, *>?
}

sealed class SuccessStatus
object Created : SuccessStatus()
object Updated : SuccessStatus()

object NoConstructorForCommand : CommandError
object AggregateAlreadyExists : CommandError
object AggregateNotFound : CommandError
