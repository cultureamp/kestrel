import eventsourcing.*
import survey.demo.*
import survey.design.*
import survey.thing.AlwaysBoppable
import survey.thing.ThingAggregate

fun main() {
    val readWriteDatabase: ReadWriteDatabase = InMemoryReadWriteDatabase()
    val readOnlyDatabase: ReadOnlyDatabase = readWriteDatabase

    val surveyNamesCommandProjection = SurveyNamesCommandProjection(readOnlyDatabase)
    val thingCommandProjection = AlwaysBoppable

    val registry = listOf(
        Configuration.from(
            SurveyCaptureLayoutAggregate.Companion::create,
            SurveyCaptureLayoutAggregate::update,
            SurveyCaptureLayoutAggregate.Companion::created,
            SurveyCaptureLayoutAggregate::updated
        ),
        Configuration.from(
            SurveyAggregate.Companion::create.partial(surveyNamesCommandProjection),
            SurveyAggregate::update.partial2(surveyNamesCommandProjection),
            ::SurveyAggregate,
            SurveyAggregate::updated
        ),
        Configuration.from(
            SurveySagaAggregate.Companion::create,
            SurveySagaAggregate::update,
            ::SurveySagaAggregate
        ),
        Configuration.from(
            PaymentSagaAggregate::create,
            PaymentSagaAggregate::update,
            PaymentSagaAggregate
        ),
        Configuration.from(ThingAggregate, thingCommandProjection)
    )
    val dbConfig = DatabaseConfig.fromEnvironment("EVENT_STORE")
    val eventStoreDataSource = dbConfig.toDataSource("event_store")
    val eventStore = createEventStore(eventStoreDataSource)
    val commandGateway = CommandGateway(eventStore, registry)

    // downstream from events
    val paymentService = PaymentService()
    val emailService = EmailService()
    val paymentSagaReactor = PaymentSagaReactor(commandGateway, paymentService, emailService, readWriteDatabase)
    val surveySagaReactor = SurveySagaReactor(commandGateway)
    val surveyNamesProjector = SurveyNamesCommandProjector(readWriteDatabase)
    val surveyCommandProjector = SurveyCommandProjector(readWriteDatabase)

    // TODO this should be done as separate threads/workers that poll the event-store
    eventStore.listeners = listOf(
        EventListener.from(paymentSagaReactor::react),
        EventListener.from(surveySagaReactor::react),
        EventListener.from(surveyNamesProjector::project),
        EventListener.from(surveyCommandProjector::first, surveyCommandProjector::second)
    )

    Ktor.startEmbeddedCommandServer(commandGateway, eventStore)
}
