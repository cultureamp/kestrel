package app

import eventsourcing.AggregateConstructor
import eventsourcing.AggregateRootRegistry
import eventsourcing.Error
import survey.design.SurveyAggregate
import survey.design.SurveyCreationCommand
import survey.design.SurveyCreationEvent
import survey.design.SurveyError
import thing.CreateThing
import thing.ThingAggregate
import thing.ThingCreationCommand
import thing.ThingCreationEvent
import java.util.*

class App {
    companion object {
        fun appWiring() {
            val first: AggregateConstructor<SurveyCreationCommand, SurveyCreationEvent, SurveyError, SurveyAggregate> = SurveyAggregate.Companion
            val second: AggregateConstructor<ThingCreationCommand, ThingCreationEvent, Error, ThingAggregate> = ThingAggregate.Companion
            val constructors = listOf(first, second)
            val aggregateRootRegistry = AggregateRootRegistry(constructors)
            val aggregateRootConstructor = aggregateRootRegistry.aggregateRootConstructorFor(CreateThing(UUID.randomUUID()))
        }
    }

}