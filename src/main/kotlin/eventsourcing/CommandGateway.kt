package eventsourcing

import kotlin.reflect.KClass

@Suppress("UNCHECKED_CAST")
class CommandGateway(
    private val eventStore: EventStore,
    private val aggregateConstructors: Map<KClass<out Command>, Configuration<*,*,*,*,*>>,
    sagas: Map<KClass<out Command>, AggregateConstructorWithProjection<*, *, Step, *, CommandGateway, *>>
) {
    private val sagaConstructors = sagas.mapValues { (_, value) -> value.curried(this) }.toList()

    fun dispatch(command: Command): Either<CommandError, SuccessStatus> = when (command) {
        is CreationCommand -> construct(command)
        is UpdateCommand -> update(command)
        else -> Left(UnrecognizedCommandType)
    }

    private fun construct(creationCommand: CreationCommand): Either<CommandError, SuccessStatus> {
        return when (eventStore.isTaken(creationCommand.aggregateId)) {
            true -> Left(AggregateIdAlreadyTaken)
            false -> {
                val sagaConstructor = null //sagaConstructorFor(creationCommand) as AggregateConstructor<CreationCommand, *, *, *, *>?
                val aggregateConstructor = aggregateConstructorFor(creationCommand) as Configuration<CreationCommand, CreationEvent, *, *, *>?
                when {
//                    sagaConstructor != null -> construct(sagaConstructor, creationCommand, ::step)
                    aggregateConstructor != null -> construct(aggregateConstructor, creationCommand)
                    else -> Left(NoConstructorForCommand)
                }
            }
        }
    }

    private fun construct(configuration: Configuration<CreationCommand, CreationEvent, *, *, *>, creationCommand: CreationCommand, stepfn: (Aggregate<Step, *, *>) -> Either<CommandError, List<UpdateEvent>> = { Right(emptyList())}): Either<CommandError, SuccessStatus> {
        val result = configuration.create(creationCommand)
        return when (result) {
            is Left -> result
            is Right -> {
                val creationEvent = result.value
                val aggregate = configuration.created(creationEvent)
                val events = listOf(creationEvent)
                eventStore.sink(configuration.aggregateType, events)
                Right(stepfn(aggregate as Aggregate<Step, *, *>)).map { Created }
            }
        }
    }

//    private tailrec fun step(saga: Aggregate<Step, *, *>): Either<CommandError, List<UpdateEvent>> {
//        val result = saga.update(Step(saga.aggregateId))
//        return when (result) {
//            is Left -> result
//            is Right -> when (result.value) {
//                emptyList<UpdateEvent>() -> result
//                else -> {
//                    val updated = updated(saga, result.value) as Aggregate<Step, *, *>
//                    eventStore.sink(updated.aggregateType(), result.value)
//                    step(updated)
//                }
//            }
//        }
//    }

    private fun update(updateCommand: UpdateCommand): Either<CommandError, SuccessStatus> {
        val configuration = aggregateConstructorFor(updateCommand) as Configuration<*, CreationEvent, *, UpdateEvent, Any>?
        return configuration?.let {
            val (creationEvent, updateEvents) = eventStore.eventsFor(updateCommand.aggregateId)
            val aggregate = updated(configuration.created(creationEvent), configuration, updateEvents)
            val result = (aggregate as Aggregate<UpdateCommand, *, *>).update(updateCommand)
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
            configuration.updated(aggregate, updateEvent) as Aggregate<*, UpdateEvent, *>
        }
    }

    private fun sagaConstructorFor(command: Command) = sagaConstructors.toList().find { entry -> entry.first.isInstance(command) }?.second
    private fun aggregateConstructorFor(command: Command) = aggregateConstructors.toList().find { entry -> entry.first.isInstance(command) }?.second
}

sealed class SuccessStatus
object Created : SuccessStatus()
object Updated : SuccessStatus()

object UnrecognizedCommandType : CommandError
object NoConstructorForCommand : CommandError
object AggregateIdAlreadyTaken : CommandError
