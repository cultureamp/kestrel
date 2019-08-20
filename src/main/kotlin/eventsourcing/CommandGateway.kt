package eventsourcing

import survey.design.*
import survey.thing.ThingAggregate
import survey.thing.ThingCommand
import kotlin.reflect.KClass

@Suppress("UNCHECKED_CAST")
class CommandGateway(private val eventStore: EventStore, surveyNamesProjection: SurveyNamesProjection) {
    val constructorRegistry: Map<KClass<out Command>, AggregateConstructor<*, *, *, *, *, *>> = mapOf(
        ThingCommand::class to ThingAggregate,
        SurveyCaptureLayoutCommand::class to SurveyCaptureLayoutAggregate,
        SurveyCommand::class to SurveyAggregate.curried(surveyNamesProjection)
    )

    fun dispatch(command: Command): Boolean = when (command) {
        is CreationCommand -> construct(command)
        is UpdateCommand -> update(command)
        else -> TODO("Handle the case where command is both Update and Create type")
    }

    private fun construct(creationCommand: CreationCommand): Boolean { // TODO return proper error codes
        // TODO fail if aggregate already exists
        val constructor = constructorFor(creationCommand) as AggregateConstructor<CreationCommand, *, *, *, *, *>?
        return constructor?.create(creationCommand)?.let { result ->
            when (result) {
                is Left -> false
                is Right -> {
                    val creationEvent = result.value
                    val aggregate = (constructor as AggregateConstructor<*, CreationEvent, *, *, *, *>).created(creationEvent)
                    eventStore.sink(aggregate::class.simpleName!!, listOf(creationEvent))
                    true
                }
            }
        } ?: false
    }

    private fun update(updateCommand: UpdateCommand): Boolean {
        val constructor = constructorFor(updateCommand) as AggregateConstructor<*, CreationEvent, *, *, *, *>?
        return constructor?.let {
            val (creationEvent, updateEvents) = eventStore.eventsFor(updateCommand.aggregateId)
            val aggregate = updated(constructor.created(creationEvent), updateEvents)
            val result = (aggregate as Aggregate<UpdateCommand, *, *, *>).update(updateCommand)
            when (result) {
                is Left -> false
                is Right -> {
                    val events = result.value
                    val updated = updated(aggregate, events)
                    eventStore.sink(updated::class.simpleName!!, events)
                    true
                }
            }
        } ?: false
    }

    private fun updated(initial: Aggregate<*, *, *, *>, updateEvents: List<UpdateEvent>): Aggregate<*, UpdateEvent, *, *> {
        return updateEvents.fold(initial as Aggregate<*, UpdateEvent, *, *>) { aggregate, updateEvent ->
            aggregate.updated(updateEvent) as Aggregate<*, UpdateEvent, *, *>
        }
    }

    private fun constructorFor(command: Command) = constructorRegistry.entries.find { entry -> entry.key.isInstance(command) }?.value
}
