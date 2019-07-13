package survey.design

import eventsourcing.*
import java.util.UUID
import java.util.Date

data class SurveyAggregate(override val aggregateId: UUID, val name: Map<Locale, String>, val accountId: UUID, val deleted: Boolean = false) : Aggregate<SurveyUpdateCommand, SurveyUpdateEvent, SurveyError, SurveyAggregate> {
    companion object : AggregateConstructor<SurveyCreationCommand, SurveyCreationEvent, SurveyError, SurveyAggregate> {
        override fun create(event: SurveyCreationEvent): SurveyAggregate = when (event) {
            is Created -> SurveyAggregate(event.aggregateId, event.name, event.accountId)
            is Snapshot -> SurveyAggregate(event.aggregateId, event.name, event.accountId, event.deleted)
        }

        override fun handle(command: SurveyCreationCommand): Result<SurveyCreationEvent, SurveyError> = when (command) {
            is Create -> Result.Success(Created(command.aggregateId, command.name, command.accountId, command.createdAt))
        }
    }

    override fun update(event: SurveyUpdateEvent): SurveyAggregate = when (event) {
        is Renamed -> this.copy(name = name + (event.locale to event.name))
        is Deleted -> this.copy(deleted = true)
        is Restored -> this.copy(deleted = false)
    }

    override fun handle(command: SurveyUpdateCommand): Result<SurveyUpdateEvent, SurveyError> = when (command) {
        is Rename -> when (name.get(command.locale)) {
            command.newName -> Result.Failure(AlreadyRenamed)
            else -> Result.Success(Renamed(command.newName, command.locale, command.renamedAt))
        }
        is Delete -> when (deleted) {
            true -> Result.Failure(AlreadyDeleted)
            false -> Result.Success(Deleted(command.deletedAt))
        }
        is Restore -> when (deleted) {
            true -> Result.Success(Restored(command.restoredAt))
            false -> Result.Failure(NotDeleted)
        }
    }
}

sealed class SurveyCommand

sealed class SurveyCreationCommand : SurveyCommand(), CreationCommand
data class Create(override val aggregateId: UUID, val name: Map<Locale, String>, val accountId: UUID, val createdAt: Date) : SurveyCreationCommand()

sealed class SurveyUpdateCommand : SurveyCommand(), UpdateCommand
data class Rename(override val aggregateId: UUID, val newName: String, val locale: Locale, val renamedAt: Date) : SurveyUpdateCommand()
data class Delete(override val aggregateId: UUID, val deletedAt: Date) : SurveyUpdateCommand()
data class Restore(override val aggregateId: UUID, val restoredAt: Date) : SurveyUpdateCommand()


sealed class SurveyEvent

sealed class SurveyCreationEvent : SurveyEvent(), CreationEvent
data class Created(override val aggregateId: UUID, val name: Map<Locale, String>, val accountId: UUID, val createdAt: Date) : SurveyCreationEvent()
data class Snapshot(override val aggregateId: UUID, val name: Map<Locale, String>, val accountId: UUID, val deleted: Boolean, val snapshottedAt: Date) : SurveyCreationEvent()

sealed class SurveyUpdateEvent : SurveyEvent(), UpdateEvent
data class Renamed(val name: String, val locale: Locale, val namedAt: Date) : SurveyUpdateEvent()
data class Deleted(val deletedAt: Date) : SurveyUpdateEvent()
data class Restored(val restoredAt: Date) : SurveyUpdateEvent()


sealed class SurveyError : Error
object AlreadyRenamed : SurveyError()
object AlreadyDeleted : SurveyError()
object NotDeleted : SurveyError()

enum class Locale {
    en, de
}

