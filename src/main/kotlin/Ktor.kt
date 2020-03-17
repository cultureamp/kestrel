import com.cultureamp.eventsourcing.*
import com.fasterxml.jackson.core.json.ReaderBasedJsonParser
import com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS
import com.fasterxml.jackson.databind.exc.MismatchedInputException
import com.fasterxml.jackson.datatype.joda.JodaModule
import com.fasterxml.jackson.module.kotlin.MissingKotlinParameterException
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
import java.io.PrintWriter
import java.io.StringWriter
import java.util.UUID
import kotlin.reflect.KClass


object Ktor {
    fun startEmbeddedCommandServer(commandGateway: CommandGateway, eventStore: EventStore) {
        embeddedServer(Netty, 8080) {
            install(ContentNegotiation) {
                jackson {
                    registerModule(JodaModule()).configure(WRITE_DATES_AS_TIMESTAMPS , false);
                }
            }
            routing {
                post("/command/{command}") {
                    try {
                        val command = command(call.parameters["command"]!!)
                        val (statusCode, message) = when (command) {
                            is Left -> when(command.error) {
                                null -> Pair(HttpStatusCode.InternalServerError, "Something went wrong")
                                else -> Pair(HttpStatusCode.BadRequest, command.error)
                            }
                            is Right -> {
                                val result = commandGateway.dispatch(command.value)
                                when (result) {
                                    is Right -> {
                                        val statusCode = successToStatusCode(result.value)
                                        // TODO this shouldn't exist in the production code - just here for debugging
                                        val events = eventStore.eventsFor(command.value.aggregateId)
                                        val domainEvents = events.map { it.domainEvent }
                                        Pair(statusCode, AggregateData(command.value.aggregateId, domainEvents.map { EventData(it::class.simpleName!!, it) }))
                                    }
                                    is Left -> {
                                        val statusCode = errorToStatusCode(result.error)
                                        Pair(statusCode, command.value)
                                    }
                                }
                            }
                        }
                        call.respond(
                            status = statusCode,
                            message = message ?: "Something went wrong"
                        )
                    } catch (e: Throwable) {
                        logStacktrace(e)
                        call.respond(
                            status = HttpStatusCode.InternalServerError,
                            message = "Something went wrong"
                        )
                    }
                }
            }
        }.start(wait = true)
    }
}

data class AggregateData(val aggregateId: UUID, val events: List<EventData>)
data class EventData(val type: String, val data: DomainEvent)
data class BadData(val field: String, val invalidValue: String?)

@Suppress("UNCHECKED_CAST")
private suspend fun PipelineContext<Unit, ApplicationCall>.command(commandClassName: String): Either<BadData?, Command> {
    return try {
        // TODO check if Class.forName is always secure - can you hydrate dangerous classes? prepend package name for safety?
        Right(call.receive(Class.forName(commandClassName).kotlin as KClass<Command>))
    } catch (e: ClassNotFoundException) {
        Left(BadData("commandClassName", commandClassName))
    } catch (e: ClassCastException) {
        Left(BadData("commandClassType", commandClassName))
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

private fun errorToStatusCode(commandError: CommandError) = when (commandError) {
    is AlreadyActionedCommandError -> HttpStatusCode.NotModified
    is AuthorizationCommandError -> HttpStatusCode.Unauthorized
    is AggregateAlreadyExists -> HttpStatusCode.Conflict
    is NoConstructorForCommand -> HttpStatusCode.NotImplemented
    else -> HttpStatusCode.Forbidden
}

private fun successToStatusCode(successStatus: SuccessStatus) = when (successStatus) {
    is Created -> HttpStatusCode.Created
    is Updated -> HttpStatusCode.OK
}

private fun logStacktrace(e: Throwable) {
    val sw = StringWriter()
    e.printStackTrace(PrintWriter(sw))
    print(sw.toString())
}
