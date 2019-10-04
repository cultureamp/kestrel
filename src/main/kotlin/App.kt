import eventsourcing.*
import survey.demo.*
import survey.design.*
import survey.thing.AlwaysBoppable
import survey.thing.ThingAggregate
import survey.thing.ThingCommand

fun main() {
    val readOnlyDatabase = StubReadOnlyDatabase
    val readWriteDatabase = InMemoryReadWriteDatabase

    val surveyNamesProjection = StubSurveyNamesProjection
    val thingProjection = AlwaysBoppable

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
            ::PaymentSagaAggregate,
            PaymentSagaAggregate.Companion::create,
            PaymentSagaAggregate::updated,
            PaymentSagaAggregate::update
        ),
        ThingCommand::class to ThingAggregate.partial(thingProjection).toConfiguration()
    )
    val eventStore = InMemoryEventStore()
    val commandGateway = CommandGateway(eventStore, aggregates)

    // downstream from events
    val paymentService = PaymentService()
    val emailService = EmailService()
    val paymentSagaReactor = PaymentSagaReactor(commandGateway, paymentService, emailService, readWriteDatabase)
    val surveySagaReactor = SurveySagaReactor(commandGateway)
    val surveyNamesProjector = SurveyNamesProjector(readWriteDatabase)

    // TODO this should be done as separate threads/works that poll the event-store
    eventStore.listeners = listOf(
        EventListener(PaymentSagaEvent::class, paymentSagaReactor::react),
        EventListener(SurveySagaEvent::class, surveySagaReactor::react),
        EventListener(SurveyEvent::class, surveyNamesProjector::project)
    )

    Ktor.startEmbeddedCommandServer(commandGateway, eventStore)
}
