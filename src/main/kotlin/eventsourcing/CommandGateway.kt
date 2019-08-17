package eventsourcing

import survey.design.SurveyCaptureLayoutAggregate
import survey.design.SurveyCaptureLayoutCommand
import survey.thing.ThingAggregate
import survey.thing.ThingCommand

class CommandGateway(val eventStore: EventStore) {
    val constructorRegistry = mapOf(
        ThingCommand::class to ThingAggregate,
        SurveyCaptureLayoutCommand::class to SurveyCaptureLayoutAggregate
    )

    fun dispatch(command: Command): Boolean = when (command) {
        is CreationCommand -> construct(command)
        is UpdateCommand -> update(command)
        else -> TODO("Handle the case where command is both Update and Create type")
    }

    private fun construct(creationCommand: CreationCommand): Boolean { // TODO return proper error codes
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
                    val updateEvents = result.value
                    val updated = updated(aggregate, updateEvents)
                    eventStore.sink(updated::class.simpleName!!, updateEvents)
                    true
                }
            }
        } ?: false
    }

    private fun updated(aggregate: Aggregate<*, *, *, *>, updateEvents: List<UpdateEvent>): Aggregate<*, UpdateEvent, *, *> {
        return updateEvents.fold(aggregate as Aggregate<*, UpdateEvent, *, *>) { aggregate, updateEvent ->
            aggregate.updated(updateEvent) as Aggregate<*, UpdateEvent, *, *>
        }
    }


    private fun constructorFor(command: Command) = constructorRegistry.entries.find { entry -> entry.key.isInstance(command) }?.value
}