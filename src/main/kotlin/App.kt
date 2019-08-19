import io.ktor.application.*
import io.ktor.http.*
import io.ktor.response.*
import io.ktor.routing.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*

fun main(args: Array<String>) {
    embeddedServer(Netty, 8080) {
        routing {
            post("/command/{command}") {
                val command = call.parameters["command"]
                call.respond(status = HttpStatusCode.Created, message = "boobar")
            }
        }
    }.start(wait = true)
}