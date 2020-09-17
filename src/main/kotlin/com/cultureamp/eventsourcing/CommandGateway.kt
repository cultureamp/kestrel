package com.cultureamp.eventsourcing

class CommandGateway<in M: EventMetadata>(private val eventStore: EventStore<M>, private val routes: List<Route<*, *>>) {

    tailrec fun dispatch(command: Command, metadata: M, retries: Int = 5): Either<CommandError, SuccessStatus> {
        val result = createOrUpdate(command, metadata)
        return if (result is Left && result.error is RetriableError && retries > 0) {
            Thread.sleep(500L)
            dispatch(command, metadata, retries - 1)
        } else {
            result
        }
    }

    private fun createOrUpdate(command: Command, metadata: M): Either<CommandError, SuccessStatus> {
        val constructor = constructorFor(command) ?: return Left(NoConstructorForCommand)
        val events = eventStore.eventsFor(command.aggregateId)
        return if (events.isEmpty()) when (command) {
            is CreationCommand -> constructor.create(command, metadata, eventStore).map { Created }
            else -> Left(AggregateNotFound)
        } else when (command) {
            is UpdateCommand -> constructor.update(command, metadata, events, eventStore).map { Updated }
            else -> Left(AggregateAlreadyExists)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun constructorFor(command: Command): AggregateConstructor<CreationCommand, CreationEvent, DomainError, UpdateCommand, UpdateEvent, Aggregate<UpdateCommand, UpdateEvent, DomainError, *>>? {
        val route = routes.find { it.creationCommandClass.isInstance(command) || it.updateCommandClass.isInstance(command) }
        return route?.aggregateConstructor as AggregateConstructor<CreationCommand, CreationEvent, DomainError, UpdateCommand, UpdateEvent, Aggregate<UpdateCommand, UpdateEvent, DomainError, *>>?
    }
}

sealed class SuccessStatus
object Created : SuccessStatus()
object Updated : SuccessStatus()

object NoConstructorForCommand : CommandError
object AggregateAlreadyExists : CommandError
object AggregateNotFound : CommandError
