import eventsourcing.*
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
import java.lang.Exception
import java.text.DateFormat
import kotlin.reflect.KClass

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
                try {
                    val commandClassName = call.parameters["command"]!!
                    val commandClass = Class.forName(commandClassName).kotlin as KClass<Command>
                    val command = call.receive(commandClass)
                    val statusCode = if (commandController.handle(command)) HttpStatusCode.Created else HttpStatusCode.InternalServerError
                    eventStore.eventsFor(command.aggregateId)
                    call.respond(status = statusCode, message = commandClass)
                } catch (e: Exception) {
                    print(e)
                    throw e
                }
            }
        }
    }.start(wait = true)
}