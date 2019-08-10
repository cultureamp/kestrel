package eventsourcing

import survey.design.*
import survey.design.Locale
import java.util.*


class CommandController {
    val commandDispatcher = CommandDispatcher(ConstructorRepository(), EventStore())

    fun handle(request: Request): Boolean {
        val command = Jackson().commandFrom(request)
        return commandDispatcher.dispatch(command)
    }
}

data class Request(val path: String, val json: String)


class Jackson {
    fun commandFrom(request: Request): Command {
        val mixedCreateAndUpdateCommandsFromDifferentAggregates: Set<Command> = setOf(
            Create(UUID.randomUUID(), UUID.randomUUID(), emptyMap(), UUID.randomUUID(), Date()), // used in two aggregates
            Rename(UUID.randomUUID(), "rename", Locale.en, Date()),
            RemoveSectionDescriptions(UUID.randomUUID(), UUID.randomUUID(), Date())
        )
        return mixedCreateAndUpdateCommandsFromDifferentAggregates.random()
    }
}