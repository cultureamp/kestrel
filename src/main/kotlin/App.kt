import eventsourcing.*
import survey.design.*
import survey.thing.ThingAggregate
import survey.thing.ThingCommand
import kotlin.reflect.KClass

fun main() {
    val surveyNamesProjection = StubSurveyNamesProjection
    val commandToConstructor: Map<KClass<out Command>, AggregateConstructor<*, *, *, *, *, *>> = mapOf(
        ThingCommand::class to ThingAggregate,
        SurveyCaptureLayoutCommand::class to SurveyCaptureLayoutAggregate,
        SurveyCommand::class to SurveyAggregate.curried(surveyNamesProjection)
    )
    val eventStore = InMemoryEventStore
    val commandGateway = CommandGateway(eventStore, commandToConstructor)

    Ktor.startEmbeddedCommandServer(commandGateway, eventStore)
}
