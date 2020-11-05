package com.cultureamp.eventsourcing

class CommandGateway<in M: EventMetadata>(private val eventStore: EventStore<M>, private val routes: List<Route<*, *, *>>) {

    tailrec fun <E : DomainError> dispatch(command: Command<E>, metadata: M, retries: Int = 5): Result<Either<SystemError, E>, SuccessStatus> {
        val result = createOrUpdate(command, metadata)
        return if (isConcurrencyError(result) && retries > 0) {
            Thread.sleep(500L)
            dispatch(command, metadata, retries - 1)
        } else {
            result
        }
    }

    private fun <E : DomainError> isConcurrencyError(result: Result<Either<SystemError, E>, SuccessStatus>) =
        result is Failure && result.error is Left && result.error.value is ConcurrencyError

    private fun <E : DomainError> createOrUpdate(command: Command<E>, metadata: M): Result<Either<SystemError, E>, SuccessStatus> {
        val constructor = constructorFor(command) ?: return Failure(Left(NoConstructorForCommand))
        val events = eventStore.eventsFor(command.aggregateId)
        return if (events.isEmpty()) when (command) {
            is CreationCommand -> constructor.create(command, metadata, eventStore).map { Created }
            else -> Failure(Left(AggregateNotFound))
        } else when (command) {
            is UpdateCommand -> constructor.update(command, metadata, events, eventStore).map { Updated }
            else -> Failure(Left(AggregateAlreadyExists))
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <E : DomainError> constructorFor(command: Command<E>): AggregateConstructor<CreationCommand<E>, CreationEvent, E, UpdateCommand<E>, UpdateEvent, Aggregate<UpdateCommand<E>, UpdateEvent, E, *>>? {
        val route = routes.find { it.creationCommandClass.isInstance(command) || it.updateCommandClass.isInstance(command) }
        return route?.aggregateConstructor as AggregateConstructor<CreationCommand<E>, CreationEvent, E, UpdateCommand<E>, UpdateEvent, Aggregate<UpdateCommand<E>, UpdateEvent, E, *>>?
    }

}

sealed class SuccessStatus
object Created : SuccessStatus()
object Updated : SuccessStatus()

sealed class SystemError : CommandError
object NoConstructorForCommand : SystemError()
object AggregateAlreadyExists : SystemError()
object AggregateNotFound : SystemError()
object ConcurrencyError : SystemError()
