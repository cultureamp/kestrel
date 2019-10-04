import eventsourcing.*
import survey.demo.EmailService
import survey.demo.PaymentSaga
import survey.demo.PaymentSagaCommand
import survey.demo.PaymentService
import survey.design.*
import survey.thing.ThingAggregate
import survey.thing.ThingCommand

fun main() {
    val readOnlyDatabase = StubReadOnlyDatabase
    val readWriteDatabase = StubReadWriteDatabase
    val surveyNamesProjection = StubSurveyNamesProjection

    val paymentService = PaymentService()
    val emailService = EmailService()

    val aggregates = mapOf(
        SurveyCaptureLayoutCommand::class to Configuration(
            ::SurveyCaptureLayoutAggregate,
            SurveyCaptureLayoutAggregate.Companion::create,
            SurveyCaptureLayoutAggregate::updated,
            SurveyCaptureLayoutAggregate::update
        ),
        SurveyCommand::class to Configuration(
            SurveyAggregate.Companion::created,
            SurveyAggregate.Companion::create.partial(surveyNamesProjection),
            SurveyAggregate::updated,
            SurveyAggregate::update.partial2(surveyNamesProjection)
        ),
        SurveySagaCommand::class to Configuration(
            ::SurveySagaAggregate,
            SurveySagaAggregate.Companion::create,
            SurveySagaAggregate::updated,
            SurveySagaAggregate::update
        ),
        PaymentSagaCommand::class to Configuration(
            ::PaymentSaga,
            PaymentSaga.Companion::create,
            PaymentSaga::updated,
            PaymentSaga::update
        ),
        ThingCommand::class to ThingAggregate.toConfiguration()
    )
    val eventStore = InMemoryEventStore()
    val commandGateway = CommandGateway(eventStore, aggregates)

    // downstream from events
    val surveySagaReactor = SurveySagaReactor(commandGateway)
    val surveyNamesProjector = SurveyNamesProjector(readWriteDatabase)

    // TODO this should be done as separate threads/works that poll the event-store
    eventStore.listeners = listOf(
        EventListener(SurveySagaEvent::class, surveySagaReactor::react),
        EventListener(SurveyEvent::class, surveyNamesProjector::project)
    )

    Ktor.startEmbeddedCommandServer(commandGateway, eventStore)
}
