package com.cultureamp.eventsourcing

import kotlinx.coroutines.delay

class CommandGateway(private val eventStore: EventStore, private val registry: List<Configuration<*, *, *, *, *, *>>) {

    tailrec suspend fun dispatch(command: Command, metadata: EventMetadata, retries: Int = 5): Either<CommandError, SuccessStatus> {
        val result = createOrUpdate(command, metadata)
        return if (result is Left && result.error is RetriableError && retries > 0) {
            delay(500L)
            dispatch(command, metadata, retries - 1)
        } else {
            result
        }
    }

    private fun createOrUpdate(command: Command, metadata: EventMetadata): Either<CommandError, SuccessStatus> {
        val configuration = configurationFor(command) ?: return Left(NoConstructorForCommand)
        val events = eventStore.eventsFor(command.aggregateId)
        return if (events.isEmpty()) when (command) {
            is CreationCommand -> configuration.create(command, metadata, eventStore).map { Created }
            else -> Left(AggregateNotFound)
        } else when (command) {
            is UpdateCommand -> configuration.update(command, metadata, events, eventStore).map { Updated }
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
