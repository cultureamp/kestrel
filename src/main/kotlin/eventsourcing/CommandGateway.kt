package eventsourcing

import io.ktor.http.HttpStatusCode
import kotlin.reflect.KClass

@Suppress("UNCHECKED_CAST")
class CommandGateway(
    private val eventStore: EventStore,
    aggregates: Map<KClass<out Command>, AggregateConstructor<*, *, *, *, *, *>>,
    sagas: Map<KClass<out Command>, AggregateConstructorWithProjection<*, *, *, *, *, CommandGateway, *>>
) {
    val constructors = sagas.mapValues { (_, value) -> value.curried(this) }.toList() + aggregates.toList()

    fun dispatch(command: Command): HttpStatusCode = when (command) {
        is CreationCommand -> construct(command)
        is UpdateCommand -> update(command)
        else -> HttpStatusCode.NotImplemented
    }

    private fun construct(creationCommand: CreationCommand): HttpStatusCode {
        return when (eventStore.isTaken(creationCommand.aggregateId)) {
            true -> HttpStatusCode.Conflict
            false -> {
                val constructor = constructorFor(creationCommand) as AggregateConstructor<CreationCommand, *, *, *, *, *>?
                return constructor?.create(creationCommand)?.let { result ->
                    when (result) {
                        is Left -> errorToStatusCode(result.error)
                        is Right -> {
                            val (creationEvent, updateEvents) = result.value
                            val aggregate = (constructor as AggregateConstructor<*, CreationEvent, *, *, *, *>).created(creationEvent)
                            val updated = updated(aggregate, updateEvents)
                            eventStore.sink(updated.aggregateType(), listOf(creationEvent) + updateEvents)
                            HttpStatusCode.Created
                        }
                    }
                } ?: HttpStatusCode.InternalServerError
            }
        }
    }

    private fun update(updateCommand: UpdateCommand): HttpStatusCode {
        val constructor = constructorFor(updateCommand) as AggregateConstructor<*, CreationEvent, *, *, *, *>?
        return constructor?.let {
            val (creationEvent, updateEvents) = eventStore.eventsFor(updateCommand.aggregateId)
            val aggregate = updated(constructor.created(creationEvent), updateEvents)
            val result = (aggregate as Aggregate<UpdateCommand, *, *, *>).update(updateCommand)
            when (result) {
                is Left -> errorToStatusCode(result.error)
                is Right -> {
                    val events = result.value
                    val updated = updated(aggregate, events)
                    eventStore.sink(updated.aggregateType(), events)
                    HttpStatusCode.OK
                }
            }
        } ?: HttpStatusCode.InternalServerError
    }

    private fun errorToStatusCode(commandError: CommandError): HttpStatusCode {
        return when (commandError) {
            is AlreadyActionedCommandError -> HttpStatusCode.NotModified
            is AuthorizationCommandError -> HttpStatusCode.Forbidden
            else -> HttpStatusCode.BadRequest
        }
    }

    private fun updated(initial: Aggregate<*, *, *, *>, updateEvents: List<UpdateEvent>): Aggregate<*, UpdateEvent, *, *> {
        return updateEvents.fold(initial as Aggregate<*, UpdateEvent, *, *>) { aggregate, updateEvent ->
            aggregate.updated(updateEvent) as Aggregate<*, UpdateEvent, *, *>
        }
    }

    private fun constructorFor(command: Command) = constructors.find { entry -> entry.first.isInstance(command) }?.second
}
