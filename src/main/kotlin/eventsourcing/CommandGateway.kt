package eventsourcing

import survey.design.SurveyCaptureLayoutAggregate
import survey.design.SurveyCaptureLayoutCommand
import survey.thing.ThingAggregate
import survey.thing.ThingCreationCommand

class CommandGateway(val eventStore: EventStore) {
    val constructorRegistry = mapOf(
        ThingCreationCommand::class to ThingAggregate,
        SurveyCaptureLayoutCommand::class to SurveyCaptureLayoutAggregate
    )

    fun dispatch(command: Command): Boolean = when (command) {
        is CreationCommand -> construct(command)
        is UpdateCommand -> update(command)
        else -> TODO("Handle the case where command is both Update and Create type")
    }

    private fun construct(creationCommand: CreationCommand): Boolean { // TODO return proper error codes
        val constructor = constructorRegistry.entries.find { entry -> entry.key.isInstance(creationCommand) }?.value as AggregateConstructor<CreationCommand, *, *, *, *, *>?
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
        val constructor = constructorRegistry.entries.find { entry -> entry.key.isInstance(updateCommand) }?.value as AggregateConstructor<*, CreationEvent, *, *, *, *>?
        return constructor?.let {
            val (creationEvent, updateEvents) = eventStore.eventsFor(updateCommand.aggregateId)
            val aggregate = updateEvents.fold(constructor.created(creationEvent) as Aggregate<*, UpdateEvent, *, *>) { aggregate, updateEvent ->
                aggregate.updated(updateEvent) as Aggregate<*, UpdateEvent, *, *>
            }
            val result = (aggregate as Aggregate<UpdateCommand, *, *, *>).update(updateCommand)
            when (result) {
                is Left -> false
                is Right -> {
                    val updateEvents = result.value
                    val updated = updateEvents.fold(aggregate as Aggregate<*, UpdateEvent, *, *>) { aggregate, updateEvent ->
                        aggregate.updated(updateEvent) as Aggregate<*, UpdateEvent, *, *>
                    }
                    eventStore.sink(updated::class.simpleName!!, updateEvents)
                    true
                }
            }
            true
        } ?: false
    }
}