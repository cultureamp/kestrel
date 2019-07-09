package survey

import eventsourcing.*
import java.util.UUID
import java.util.Date

data class SurveyAggregate(override val aggregateId: UUID, val name: Map<Locale, String>, val accountId: UUID, val deleted: Boolean = false) : Aggregate<SurveyUpdateCommand, SurveyUpdateEvent, SurveyError, SurveyAggregate> {
    companion object : AggregateConstructor<SurveyCreationCommand, SurveyCreationEvent, SurveyError, SurveyAggregate> {
        override fun create(event: SurveyCreationEvent): SurveyAggregate = when (event) {
            is Created -> with(event) { SurveyAggregate(aggregateId, name, accountId) }
            is Snapshot -> with(event) { SurveyAggregate(aggregateId, name, accountId, deleted) }
        }

        override fun handle(command: SurveyCreationCommand): Result<SurveyCreationEvent, SurveyError> = when (command) {
            is Create -> with(command) { Result.Success(Created(aggregateId, name, accountId, createdAt)) }
        }
    }

    override fun update(event: SurveyUpdateEvent): SurveyAggregate = when (event) {
        is Renamed -> this.copy(name = name.plus(event.locale to event.name))
        is Deleted -> this.copy(deleted = true)
        is Restored -> this.copy(deleted = false)
    }

    override fun handle(command: SurveyUpdateCommand): Result<SurveyUpdateEvent, SurveyError> = when (command) {
        is Rename -> when (name.get(command.locale)) {
            command.newName -> Result.Failure(AlreadyRenamed)
            else -> with(command) { Result.Success(Renamed(newName, locale, renamedAt)) }
        }
        is Delete -> when (deleted) {
            true -> Result.Failure(AlreadyDeleted)
            false -> with(command) { Result.Success(Deleted(deletedAt)) }
        }
        is Restore -> when (deleted) {
            true -> with(command) { Result.Success(Restored(restoredAt)) }
            false -> Result.Failure(NotDeleted)
        }
    }
}

sealed class SurveyCreationCommand : CreationCommand
data class Create(override val aggregateId: UUID, val name: Map<Locale, String>, val accountId: UUID, val createdAt: Date) : SurveyCreationCommand()

sealed class SurveyUpdateCommand : UpdateCommand
data class Rename(override val aggregateId: UUID, val newName: String, val locale: Locale, val renamedAt: Date) : SurveyUpdateCommand()
data class Delete(override val aggregateId: UUID, val deletedAt: Date) : SurveyUpdateCommand()
data class Restore(override val aggregateId: UUID, val restoredAt: Date) : SurveyUpdateCommand()

sealed class SurveyCreationEvent : CreationEvent
data class Created(override val aggregateId: UUID, val name: Map<Locale, String>, val accountId: UUID, val createdAt: Date) : SurveyCreationEvent()
data class Snapshot(override val aggregateId: UUID, val name: Map<Locale, String>, val accountId: UUID, val deleted: Boolean, val snapshottedAt: Date) : SurveyCreationEvent()

sealed class SurveyUpdateEvent : UpdateEvent
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

