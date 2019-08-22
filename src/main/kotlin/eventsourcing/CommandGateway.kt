package eventsourcing

import io.ktor.http.HttpStatusCode
import kotlin.reflect.KClass

@Suppress("UNCHECKED_CAST")
class CommandGateway(private val eventStore: EventStore, val commandToConstructor: Map<KClass<out Command>, AggregateConstructor<*, *, *, *, *, *>>) {
    fun dispatch(command: Command): HttpStatusCode = when (command) {
        is CreationCommand -> construct(command)
        is UpdateCommand -> update(command)
        else -> HttpStatusCode.NotImplemented
    }

    private fun construct(creationCommand: CreationCommand): HttpStatusCode {
        // TODO fail if aggregate already exists and fail with HttpStatusCode.Conflict
        val constructor = constructorFor(creationCommand) as AggregateConstructor<CreationCommand, *, *, *, *, *>?
        return constructor?.create(creationCommand)?.let { result ->
            when (result) {
                is Left -> errorToStatusCode(result)
                is Right -> {
                    val creationEvent = result.value
                    val aggregate = (constructor as AggregateConstructor<*, CreationEvent, *, *, *, *>).created(creationEvent)
                    eventStore.sink(aggregate.aggregateType(), listOf(creationEvent))
                    HttpStatusCode.Created
                }
            }
        } ?: HttpStatusCode.InternalServerError
    }

    private fun errorToStatusCode(result: Left<CommandError>): HttpStatusCode {
        return when (result.error) {
            is AlreadyActionedCommandError -> HttpStatusCode.NotModified
            is AuthorizationCommandError -> HttpStatusCode.Forbidden
            else -> HttpStatusCode.BadRequest
        }
    }

    private fun update(updateCommand: UpdateCommand): HttpStatusCode {
        val constructor = constructorFor(updateCommand) as AggregateConstructor<*, CreationEvent, *, *, *, *>?
        return constructor?.let {
            val (creationEvent, updateEvents) = eventStore.eventsFor(updateCommand.aggregateId)
            val aggregate = updated(constructor.created(creationEvent), updateEvents)
            val result = (aggregate as Aggregate<UpdateCommand, *, *, *>).update(updateCommand)
            when (result) {
                is Left -> errorToStatusCode(result)
                is Right -> {
                    val events = result.value
                    val updated = updated(aggregate, events)
                    eventStore.sink(updated.aggregateType(), events)
                    HttpStatusCode.OK
                }
            }
        } ?: HttpStatusCode.InternalServerError
    }

    private fun updated(initial: Aggregate<*, *, *, *>, updateEvents: List<UpdateEvent>): Aggregate<*, UpdateEvent, *, *> {
        return updateEvents.fold(initial as Aggregate<*, UpdateEvent, *, *>) { aggregate, updateEvent ->
            aggregate.updated(updateEvent) as Aggregate<*, UpdateEvent, *, *>
        }
    }

    private fun constructorFor(command: Command) = commandToConstructor.entries.find { entry -> entry.key.isInstance(command) }?.value
}
