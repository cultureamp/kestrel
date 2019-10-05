import eventsourcing.*
import survey.demo.*
import survey.design.*
import survey.thing.AlwaysBoppable
import survey.thing.ThingAggregate

fun main() {
    val readWriteDatabase: ReadWriteDatabase = InMemoryReadWriteDatabase()
    val readOnlyDatabase: ReadOnlyDatabase = readWriteDatabase

    val surveyNamesProjection = SurveyNamesProjection(readOnlyDatabase)
    val thingProjection = AlwaysBoppable

    val aggregates = listOf(
        Configuration.from(
            ::SurveyCaptureLayoutAggregate,
            SurveyCaptureLayoutAggregate.Companion::create,
            SurveyCaptureLayoutAggregate::updated,
            SurveyCaptureLayoutAggregate::update
        ),
        Configuration.from(
            SurveyAggregate.Companion::created,
            SurveyAggregate.Companion::create.partial(surveyNamesProjection),
            SurveyAggregate::updated,
            SurveyAggregate::update.partial2(surveyNamesProjection)
        ),
        Configuration.from(
            ::SurveySagaAggregate,
            SurveySagaAggregate.Companion::create,
            SurveySagaAggregate::updated,
            SurveySagaAggregate::update
        ),
        Configuration.from(
            ::PaymentSagaAggregate,
            PaymentSagaAggregate.Companion::create,
            PaymentSagaAggregate::updated,
            PaymentSagaAggregate::update
        ),
        Configuration.from(ThingAggregate, thingProjection)
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
        EventListener.from(paymentSagaReactor::react),
        EventListener.from(surveySagaReactor::react),
        EventListener.from(surveyNamesProjector::project)
    )

    Ktor.startEmbeddedCommandServer(commandGateway, eventStore)
}
