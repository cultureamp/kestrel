package com.cultureamp.eventsourcing

import arrow.core.Either
import arrow.core.left

interface CommandGateway<M : EventMetadata> {
    companion object {
        operator fun <M : EventMetadata> invoke(eventStore: EventStore<M>, vararg routes: Route<*, *, M>) = EventStoreCommandGateway(eventStore, *routes)
    }
    fun dispatch(command: Command, metadata: M, retries: Int = 5): Either<CommandError, SuccessStatus>
}

class EventStoreCommandGateway<M : EventMetadata>(private val eventStore: EventStore<M>, private vararg val routes: Route<*, *, M>) : CommandGateway<M> {
    override tailrec fun dispatch(command: Command, metadata: M, retries: Int): Either<CommandError, SuccessStatus> {
        val result = createOrUpdate(command, metadata)
        return if (result is Either.Left && result.value is RetriableError && retries > 0) {
            Thread.sleep(500L)
            dispatch(command, metadata, retries - 1)
        } else {
            result
        }
    }

    private fun createOrUpdate(command: Command, metadata: M): Either<CommandError, SuccessStatus> {
        val constructor = constructorFor(command) ?: return NoConstructorForCommand.left()
        val events = eventStore.eventsFor(command.aggregateId)
        return if (events.isEmpty()) when (command) {
            is CreationCommand -> constructor.create(command, metadata, eventStore).map { Created }
            else -> AggregateNotFound.left()
        } else when (command) {
            is UpdateCommand -> constructor.update(command, metadata, events, eventStore).map { Updated }
            else -> AggregateAlreadyExists.left()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun constructorFor(command: Command): AggregateConstructor<CreationCommand, CreationEvent, DomainError, UpdateCommand, UpdateEvent, M, Aggregate<UpdateCommand, UpdateEvent, DomainError, M, *>>? {
        val route = routes.find { it.creationCommandClass.isInstance(command) || it.updateCommandClass.isInstance(command) }
        return route?.aggregateConstructor as AggregateConstructor<CreationCommand, CreationEvent, DomainError, UpdateCommand, UpdateEvent, M, Aggregate<UpdateCommand, UpdateEvent, DomainError, M, *>>?
    }
}

sealed class SuccessStatus
object Created : SuccessStatus()
object Updated : SuccessStatus()

object NoConstructorForCommand : CommandError
object AggregateAlreadyExists : CommandError
object AggregateNotFound : CommandError
