import eventsourcing.*
import survey.design.*
import survey.thing.ThingAggregate
import survey.thing.ThingCommand
import kotlin.reflect.KClass

fun main() {
    val surveyNamesProjection = StubSurveyNamesProjection

    val aggregates = mapOf(
        ThingCommand::class to Configuration(
            ThingAggregate.Companion::create,
            ::ThingAggregate,
            ThingAggregate::update,
            ThingAggregate::updated
        ),
        SurveyCaptureLayoutCommand::class to Configuration(
            SurveyCaptureLayoutAggregate.Companion::create,
            ::SurveyCaptureLayoutAggregate,
            SurveyCaptureLayoutAggregate::update,
            SurveyCaptureLayoutAggregate::updated
        ),
        SurveyCommand::class to Configuration(
            SurveyAggregate.Companion::create.partial(surveyNamesProjection),
            SurveyAggregate.Companion::created,
            SurveyAggregate::update.partial2(surveyNamesProjection),
            SurveyAggregate::updated
        )
    )
    val sagas: Map<KClass<out Command>, SagaConfiguration<*,*,*,*>> = mapOf(
        SurveySagaCreationCommand::class to SagaConfiguration(
            SurveySaga.Companion::create,
            ::SurveySaga,
            SurveySaga::update,
            SurveySaga::updated
        )
    )
    val eventStore = InMemoryEventStore
    val commandGateway = CommandGateway(eventStore, aggregates, sagas)

    Ktor.startEmbeddedCommandServer(commandGateway, eventStore)
}
