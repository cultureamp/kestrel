import eventsourcing.CommandController
import eventsourcing.CommandGateway
import eventsourcing.EventStore
import eventsourcing.InMemoryEventStore
import io.ktor.application.call
import io.ktor.application.install
import io.ktor.features.ContentNegotiation
import io.ktor.gson.gson
import io.ktor.http.HttpStatusCode
import io.ktor.request.receive
import io.ktor.response.respond
import io.ktor.routing.post
import io.ktor.routing.routing
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import survey.design.StubSurveyNamesProjection
import survey.thing.CreateThing
import java.text.DateFormat

fun main() {
    val surveyNamesProjection = StubSurveyNamesProjection
    val eventStore = InMemoryEventStore
    val commandGateway = CommandGateway(eventStore, surveyNamesProjection)
    val commandController = CommandController(commandGateway)
    embeddedServer(Netty, 8080) {
        install(ContentNegotiation) {
            gson {
                setDateFormat(DateFormat.LONG)
                setPrettyPrinting()
            }
        }
        routing {
            post("/command/{command}") {
                val commandClass = call.parameters["command"]!!
                val command = call.receive<CreateThing>()
                val statusCode = if (commandController.handle(command)) HttpStatusCode.Created else HttpStatusCode.InternalServerError
                eventStore.eventsFor(command.aggregateId)
                call.respond(status = statusCode, message = commandClass)
            }
        }
    }.start(wait = true)
}