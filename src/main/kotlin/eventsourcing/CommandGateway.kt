package eventsourcing

import java.util.*
import kotlin.reflect.KClass
import kotlin.reflect.KFunction3

@Suppress("UNCHECKED_CAST")
class CommandGateway(
    private val eventStore: EventStore,
    private val configurations: Map<KClass<out Command>, Configuration<*, *, *, *, *>>,
    sagas: Map<KClass<out Command>, SagaConfiguration<*, *, *, *>>
) {
    private val sagaConstructors = sagas.mapValues { (_, value) ->
        val create = value.create
        val created = value.created as (CreationEvent) -> Any
        val update = value.update.partial2(this) as (Any, Step) -> Either<CommandError, List<UpdateEvent>>
        val updated = value.updated as (Any, UpdateEvent) -> Any
        val aggregateId = value.aggregateId as (Any) -> UUID
        Configuration(create, created, update, updated, value.aggregateType, aggregateId) }.toList()

    fun dispatch(command: Command): Either<CommandError, SuccessStatus> = when (command) {
        is CreationCommand -> construct(command)
        is UpdateCommand -> update(command)
        else -> Left(UnrecognizedCommandType)
    }

    private fun construct(creationCommand: CreationCommand): Either<CommandError, SuccessStatus> {
        return when (eventStore.isTaken(creationCommand.aggregateId)) {
            true -> Left(AggregateIdAlreadyTaken)
            false -> {
                val sagaConstructor = sagaConstructorFor(creationCommand) as Configuration<CreationCommand, CreationEvent, Step, UpdateEvent, Any>?
                val aggregateConstructor = aggregateConstructorFor(creationCommand) as Configuration<CreationCommand, CreationEvent, *, *, *>?
                when {
                    sagaConstructor != null -> {
                        val stepfn = ::step
                        construct(sagaConstructor, creationCommand, stepfn)
                    }
                    aggregateConstructor != null -> construct(aggregateConstructor, creationCommand)
                    else -> Left(NoConstructorForCommand)
                }
            }
        }
    }

    private fun construct(configuration: Configuration<CreationCommand, CreationEvent, *, *, *>, creationCommand: CreationCommand, stepfn: (Any, Configuration<*,*,Step,UpdateEvent,Any>) -> Either<CommandError, List<UpdateEvent>> = { _,_ -> Right(emptyList())}): Either<CommandError, SuccessStatus> {
        val result = configuration.create(creationCommand)
        return when (result) {
            is Left -> result
            is Right -> {
                val creationEvent = result.value
                val aggregate = configuration.created(creationEvent)
                val events = listOf(creationEvent)
                eventStore.sink(configuration.aggregateType, events)
                Right(stepfn(aggregate, configuration as Configuration<CreationCommand, CreationEvent, Step, UpdateEvent, Any>)).map { Created }
            }
        }
    }

    private tailrec fun step(saga: Any, configuration: Configuration<*,*,Step,UpdateEvent,Any>): Either<CommandError, List<UpdateEvent>> {
        val result = configuration.update(saga, Step(configuration.aggregateId(saga)))
        return when (result) {
            is Left -> result
            is Right -> when (result.value) {
                emptyList<UpdateEvent>() -> result
                else -> {
                    val updated = updated(saga, configuration, result.value)
                    eventStore.sink(configuration.aggregateType, result.value)
                    step(updated, configuration)
                }
            }
        }
    }

    private fun update(updateCommand: UpdateCommand): Either<CommandError, SuccessStatus> {
        val configuration = aggregateConstructorFor(updateCommand) as Configuration<*, CreationEvent, UpdateCommand, UpdateEvent, Any>?
        return configuration?.let {
            val (creationEvent, updateEvents) = eventStore.eventsFor(updateCommand.aggregateId)
            val aggregate = updated(configuration.created(creationEvent), configuration, updateEvents)
            val result = configuration.update(aggregate, updateCommand)
            when (result) {
                is Left -> result
                is Right -> {
                    val events = result.value
                    val updated = updated(aggregate, configuration, events)
                    eventStore.sink(configuration.aggregateType, events)
                    Right(Updated)
                }
            }
        } ?: Left(NoConstructorForCommand)
    }

    private fun updated(initial: Any, configuration: Configuration<*,*,*, UpdateEvent, Any>, updateEvents: List<UpdateEvent>): Any {
        return updateEvents.fold(initial) { aggregate, updateEvent ->
            configuration.updated(aggregate, updateEvent)
        }
    }

    private fun sagaConstructorFor(command: Command) = sagaConstructors.toList().find { entry -> entry.first.isInstance(command) }?.second
    private fun aggregateConstructorFor(command: Command) = configurations.toList().find { entry -> entry.first.isInstance(command) }?.second
}

sealed class SuccessStatus
object Created : SuccessStatus()
object Updated : SuccessStatus()

object UnrecognizedCommandType : CommandError
object NoConstructorForCommand : CommandError
object AggregateIdAlreadyTaken : CommandError

fun <A,B,C,D> KFunction3<A, B, C, D>.partial2(b: B): (A, C) -> D {
    return {a,c -> invoke(a, b, c)}
}