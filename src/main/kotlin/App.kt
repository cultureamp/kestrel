import eventsourcing.*
import survey.demo.EmailService
import survey.demo.PaymentSaga
import survey.demo.PaymentSagaCommand
import survey.demo.PaymentService
import survey.design.*
import survey.thing.ThingAggregate
import survey.thing.ThingCommand

fun main() {
    val surveyNamesProjection = StubSurveyNamesProjection
    val paymentService = PaymentService()
    val emailService = EmailService()

    val aggregates = mapOf(
        ThingCommand::class to Configuration(
            ::ThingAggregate,
            ThingAggregate.Companion::create,
            ThingAggregate::updated,
            ThingAggregate::update
        ),
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
        SurveySagaCreationCommand::class to Configuration(
            ::SurveySaga,
            SurveySaga.Companion::create,
            SurveySaga::updated,
            step = SurveySaga::step
        ),
        PaymentSagaCommand::class to Configuration(
            ::PaymentSaga,
            PaymentSaga.Companion::create,
            PaymentSaga::updated,
            PaymentSaga::update,
            PaymentSaga::step.partial2(paymentService).partial2(emailService)
        )
    )
    val eventStore = InMemoryEventStore
    val commandGateway = CommandGateway(eventStore, aggregates)

    Ktor.startEmbeddedCommandServer(commandGateway, eventStore)
}
