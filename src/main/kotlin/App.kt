import com.fasterxml.jackson.core.json.ReaderBasedJsonParser
import com.fasterxml.jackson.databind.exc.MismatchedInputException
import com.fasterxml.jackson.module.kotlin.MissingKotlinParameterException
import eventsourcing.*
import io.ktor.application.ApplicationCall
import io.ktor.application.call
import io.ktor.application.install
import io.ktor.features.ContentNegotiation
import io.ktor.http.HttpStatusCode
import io.ktor.jackson.jackson
import io.ktor.request.receive
import io.ktor.response.respond
import io.ktor.routing.post
import io.ktor.routing.routing
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.util.pipeline.PipelineContext
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
                // TODO handle command missing
                val commandClassName = call.parameters["command"]!!
                // TODO handle unrecognised name
                val commandClass = Class.forName(commandClassName).kotlin as KClass<Command>
                val command: Either<BadData?, Command> = parseCommandFromJson(commandClass)
                when (command) {
                    is Left -> call.respond(
                        status = HttpStatusCode.BadRequest,
                        message = command.error ?: "Something went wrong"
                    )
                    is Right -> when (commandController.handle(command.value)) {
                        false -> call.respond(
                            status = HttpStatusCode.InternalServerError, // TODO error code breakdown
                            message = command
                        )
                        true -> {
                            val (created, updated) = eventStore.eventsFor(command.value.aggregateId)
                            val events = listOf(created) + updated
                            val eventData = events.map { EventData(it::class.simpleName!!, it) }
                            call.respond(
                                status = HttpStatusCode.Created,
                                message = eventData
                            )
                        }
                    }
                }
            }
        }
    }.start(wait = true)
}

data class EventData(val type: String, val data: Event)
data class BadData(val field: String, val invalidValue: String?)

private suspend fun PipelineContext<Unit, ApplicationCall>.parseCommandFromJson(commandClass: KClass<Command>): Either<BadData?, Command> {
    return try {
        Right(call.receive(commandClass))
    } catch (e: MismatchedInputException) {
        val field = e.path.first().fieldName
        val value = (e.processor as ReaderBasedJsonParser).text
        Left(BadData(field, value))
    } catch (e: MissingKotlinParameterException) {
        val field = e.path.first().fieldName
        Left(BadData(field, null))
    } catch (e: Exception) {
        logStacktrace(e)
        Left(null)
    }
}

fun logStacktrace(e: Exception) {
    val sw = StringWriter()
    e.printStackTrace(PrintWriter(sw))
    print(sw.toString())
}
