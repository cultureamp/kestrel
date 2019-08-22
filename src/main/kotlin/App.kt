import eventsourcing.CommandGateway
import eventsourcing.InMemoryEventStore
import eventsourcing.Ktor
import survey.design.*
import survey.thing.ThingAggregate
import survey.thing.ThingCommand

fun main() {
    val surveyNamesProjection = StubSurveyNamesProjection
    val commandToConstructor = mapOf(
        ThingCommand::class to ThingAggregate,
        SurveyCaptureLayoutCommand::class to SurveyCaptureLayoutAggregate,
        SurveyCommand::class to SurveyAggregate.curried(surveyNamesProjection)
    )
    val eventStore = InMemoryEventStore
    val commandGateway = CommandGateway(eventStore, commandToConstructor)

    Ktor.startEmbeddedCommandServer(commandGateway, eventStore)
}
