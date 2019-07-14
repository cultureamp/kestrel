package app

import eventsourcing.AggregateRootRegistry
import survey.design.SurveyAggregate
import survey.design.SurveyCaptureLayoutAggregate
import thing.CreateThing
import thing.ThingAggregate
import java.util.UUID

class App {
    companion object {
        fun appWiring() {
            val first = SurveyAggregate.Companion
            val second = ThingAggregate.Companion
            val third = SurveyCaptureLayoutAggregate.Companion
            val constructors = listOf(first, second, third)
            val aggregateRootRegistry = AggregateRootRegistry(constructors)
            val aggregateRootConstructor = aggregateRootRegistry.aggregateRootConstructorFor(CreateThing(UUID.randomUUID()))
        }
    }

}