import com.fasterxml.jackson.core.json.ReaderBasedJsonParser
import com.fasterxml.jackson.databind.exc.MismatchedInputException
import com.fasterxml.jackson.module.kotlin.MissingKotlinParameterException
import eventsourcing.Command
import eventsourcing.CommandController
import eventsourcing.CommandGateway
import eventsourcing.InMemoryEventStore
import io.ktor.application.call
import io.ktor.application.install
import io.ktor.features.ContentNegotiation
import io.ktor.http.HttpStatusCode
import io.ktor.jackson.jackson
import io.ktor.request.receive
import io.ktor.response.respond
import io.ktor.response.respondText
import io.ktor.routing.post
import io.ktor.routing.routing
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import survey.design.StubSurveyNamesProjection
import java.io.PrintWriter
import java.io.StringWriter
import kotlin.reflect.KClass

fun main() {
    val surveyNamesProjection = StubSurveyNamesProjection
    val eventStore = InMemoryEventStore
    val commandGateway = CommandGateway(eventStore, surveyNamesProjection)
    val commandController = CommandController(commandGateway)
    embeddedServer(Netty, 8080) {
        install(ContentNegotiation) {
            jackson {}
        }
        routing {
            post("/command/{command}") {
                try {
                    val commandClassName = call.parameters["command"]!!
                    val commandClass = Class.forName(commandClassName).kotlin as KClass<Command>
                    val command = call.receive(commandClass)
                    val statusCode = if (commandController.handle(command)) HttpStatusCode.Created else HttpStatusCode.InternalServerError
                    eventStore.eventsFor(command.aggregateId)
                    call.respondText(status = statusCode, text = commandClassName)
                } catch (e: MismatchedInputException) {
                    val field = e.path.first().fieldName
                    val value = (e.processor as ReaderBasedJsonParser).text
                    call.respond(status = HttpStatusCode.BadRequest, message = BadData(field, value))
                } catch (e: MissingKotlinParameterException) {
                    val field = e.path.first().fieldName
                    call.respond(status = HttpStatusCode.BadRequest, message = BadData(field, null))
                } catch (e: Exception) {
                    logStacktrace(e)
                    call.respondText(status = HttpStatusCode.InternalServerError, text = "Something went wrong")
                }
            }
        }
    }.start(wait = true)
}

data class BadData(val field: String, val invalidValue: String?)

fun logStacktrace(e: Exception) {
    val sw = StringWriter()
    e.printStackTrace(PrintWriter(sw))
    print(sw.toString())
}