import eventsourcing.*
import survey.design.*
import survey.thing.ThingAggregate
import survey.thing.ThingCommand
import kotlin.reflect.KClass

fun main() {
    val surveyNamesProjection = StubSurveyNamesProjection
    val aggregates = mapOf(
        ThingCommand::class to ThingAggregate,
        SurveyCaptureLayoutCommand::class to SurveyCaptureLayoutAggregate,
        SurveyCommand::class to SurveyAggregate.curried(surveyNamesProjection)
    )
    val sagas: Map<KClass<out Command>, AggregateConstructorWithProjection<*, *, *, Step, *, CommandGateway, *>> = mapOf(
        SurveySagaCreationCommand::class to SurveySaga
    )
    val eventStore = InMemoryEventStore
    val commandGateway = CommandGateway(eventStore, aggregates, sagas)

    Ktor.startEmbeddedCommandServer(commandGateway, eventStore)
}
