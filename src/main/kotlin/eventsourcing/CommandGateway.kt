package eventsourcing

import kotlin.reflect.KClass

@Suppress("UNCHECKED_CAST")
class CommandGateway(
    private val eventStore: EventStore,
    aggregates: Map<KClass<out Command>, Configuration<*, *, *, *, *>>,
    sagas: Map<KClass<out Command>, SagaConfiguration<*, *, *, *>>
) {
    private val sagaConfigurations = sagas.mapValues { (_, value) -> Pair(value.toConfiguration(this), ::step) }
    private val aggregateConfigurations = aggregates.mapValues { (_, value) -> Pair(value, dontStep()) }
    private val configurations = sagaConfigurations.toList() + aggregateConfigurations.toList()

    fun dispatch(command: Command): Either<CommandError, SuccessStatus> = when (command) {
        is CreationCommand -> construct(command)
        is UpdateCommand -> update(command)
        else -> Left(UnrecognizedCommandType)
    }

    private fun construct(creationCommand: CreationCommand): Either<CommandError, SuccessStatus> {
        return when (eventStore.isTaken(creationCommand.aggregateId)) {
            true -> Left(AggregateIdAlreadyTaken)
            false -> {
                return configurationFor(creationCommand)?.let { (config, step) -> construct(config, creationCommand, step)} ?: Left(NoConstructorForCommand)
            }
        }
    }

    private fun construct(configuration: Configuration<CreationCommand, CreationEvent, UpdateCommand, UpdateEvent, Aggregate>, creationCommand: CreationCommand, stepFn: (Aggregate, Configuration<CreationCommand,CreationEvent,Step,UpdateEvent,Aggregate>) -> Either<CommandError, List<UpdateEvent>>): Either<CommandError, SuccessStatus> {
        val result = configuration.create(creationCommand)
        return when (result) {
            is Left -> result
            is Right -> {
                val creationEvent = result.value
                val aggregate = configuration.created(creationEvent)
                val events = listOf(creationEvent)
                eventStore.sink(aggregate.aggregateType(), events)
                Right(stepFn(aggregate, configuration as Configuration<CreationCommand, CreationEvent, Step, UpdateEvent, Aggregate>)).map { Created }
            }
        }
    }

    private fun dontStep(): (Aggregate, Configuration<*,*,Step,UpdateEvent,Aggregate>) -> Either<CommandError, List<UpdateEvent>> = { _, _ -> Right(emptyList()) }

    private tailrec fun step(saga: Aggregate, configuration: Configuration<*, *, Step, UpdateEvent, Aggregate>): Either<CommandError, List<UpdateEvent>> {
        val result = configuration.update(saga, Step(saga.aggregateId))
        return when (result) {
            is Left -> result
            is Right -> when (result.value) {
                emptyList<UpdateEvent>() -> result
                else -> {
                    val updated = updated(saga, configuration, result.value)
                    eventStore.sink(updated.aggregateType(), result.value)
                    step(updated, configuration)
                }
            }
        }
    }

    private fun update(updateCommand: UpdateCommand): Either<CommandError, SuccessStatus> {
        val configuration = configurationFor(updateCommand)?.first
        return configuration?.let {
            val (creationEvent, updateEvents) = eventStore.eventsFor(updateCommand.aggregateId)
            val aggregate = updated(configuration.created(creationEvent), configuration, updateEvents)
            val result = configuration.update(aggregate, updateCommand)
            when (result) {
                is Left -> result
                is Right -> {
                    val events = result.value
                    val updated = updated(aggregate, configuration, events)
                    eventStore.sink(updated.aggregateType(), events)
                    Right(Updated)
                }
            }
        } ?: Left(NoConstructorForCommand)
    }

    private fun updated(initial: Aggregate, configuration: Configuration<*,*,*, UpdateEvent, Aggregate>, updateEvents: List<UpdateEvent>): Aggregate {
        return updateEvents.fold(initial) { aggregate, updateEvent ->
            configuration.updated(aggregate, updateEvent)
        }
    }

    private fun configurationFor(command: Command) =
        configurations.toList().find { entry -> entry.first.isInstance(command) }?.second as Pair<Configuration<CreationCommand, CreationEvent, UpdateCommand, UpdateEvent, Aggregate>, (Aggregate, Configuration<CreationCommand,CreationEvent,Step,UpdateEvent,Aggregate>) -> Either<CommandError, List<UpdateEvent>>>?
}

sealed class SuccessStatus
object Created : SuccessStatus()
object Updated : SuccessStatus()

object UnrecognizedCommandType : CommandError
object NoConstructorForCommand : CommandError
object AggregateIdAlreadyTaken : CommandError
