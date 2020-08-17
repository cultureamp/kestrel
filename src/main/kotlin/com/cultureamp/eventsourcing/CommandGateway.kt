package com.cultureamp.eventsourcing

import kotlin.reflect.KClass

class CommandGateway(
    private val eventStore: EventStore,
    private val registry: List<Pair<KClass<out Command>, AggregateConstructor<out CreationCommand, out CreationEvent, out UpdateCommand, out UpdateEvent>>>
) {

    tailrec fun dispatch(command: Command, metadata: EventMetadata, retries: Int = 5): Either<CommandError, SuccessStatus> {
        val result = createOrUpdate(command, metadata)
        return if (result is Left && result.error is RetriableError && retries > 0) {
            Thread.sleep(500L)
            dispatch(command, metadata, retries - 1)
        } else {
            result
        }
    }

    private fun createOrUpdate(command: Command, metadata: EventMetadata): Either<CommandError, SuccessStatus> {
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
    private fun constructorFor(command: Command) = registry.find { it.first.isInstance(command) }?.second as AggregateConstructor<CreationCommand, CreationEvent, UpdateCommand, UpdateEvent>?
}

sealed class SuccessStatus
object Created : SuccessStatus()
object Updated : SuccessStatus()

object NoConstructorForCommand : CommandError
object AggregateAlreadyExists : CommandError
object AggregateNotFound : CommandError
