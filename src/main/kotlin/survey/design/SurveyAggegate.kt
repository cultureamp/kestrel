package survey.design

import eventsourcing.AggregateConstructorWithProjection
import eventsourcing.AggregateWithProjection
import eventsourcing.Command
import eventsourcing.CommandError
import eventsourcing.CreationCommand
import eventsourcing.CreationEvent
import eventsourcing.Either
import eventsourcing.Event
import eventsourcing.Left
import eventsourcing.Right
import eventsourcing.UpdateCommand
import eventsourcing.UpdateEvent
import java.util.*

data class SurveyAggregate(override val aggregateId: UUID, val name: Map<Locale, String>, val accountId: UUID, val deleted: Boolean = false) : AggregateWithProjection<SurveyUpdateCommand, SurveyUpdateEvent, SurveyError, SurveyNamesProjection, SurveyAggregate> {

    companion object : AggregateConstructorWithProjection<SurveyCreationCommand, SurveyCreationEvent, SurveyError, SurveyNamesProjection, SurveyAggregate> {
        override fun create(event: SurveyCreationEvent): SurveyAggregate = when (event) {
            is Created -> SurveyAggregate(event.aggregateId, event.name, event.accountId)
            is Snapshot -> SurveyAggregate(event.aggregateId, event.name, event.accountId, event.deleted)
        }

        override fun create(
            command: SurveyCreationCommand,
            projection: SurveyNamesProjection
        ): Either<SurveyError, List<SurveyCreationEvent>> = when (command) {
            is Create -> when {
                command.name.any { (locale, name) -> projection.nameExistsFor(command.accountId, name, locale)} -> Left(SurveyNameNotUnique)
                else -> Right.list(Created(command.aggregateId, command.name, command.accountId, command.createdAt))
            }
        }
    }

    override fun update(event: SurveyUpdateEvent): SurveyAggregate = when (event) {
        is Renamed -> this.copy(name = name + (event.locale to event.name))
        is Deleted -> this.copy(deleted = true)
        is Restored -> this.copy(deleted = false)
    }

    override fun update(command: SurveyUpdateCommand, projection: SurveyNamesProjection): Either<SurveyError, List<SurveyUpdateEvent>> = when (command) {
        is Rename -> when {
            name.get(command.locale) == command.newName -> Left(AlreadyRenamed)
            projection.nameExistsFor(accountId, command.newName, command.locale) -> Left(SurveyNameNotUnique)
            else -> Right.list(Renamed(command.aggregateId, command.newName, command.locale, command.renamedAt))
        }
        is Delete -> when (deleted) {
            true -> Left(AlreadyDeleted)
            false -> Right.list(Deleted(command.aggregateId, command.deletedAt))
        }
        is Restore -> when (deleted) {
            true -> Right.list(Restored(command.aggregateId, command.restoredAt))
            false -> Left(NotDeleted)
        }
    }
}

sealed class SurveyCommand : Command
sealed class SurveyCreationCommand : SurveyCommand(), CreationCommand
data class Create(
    override val aggregateId: UUID,
    val surveyCaptureLayoutAggregateId: UUID,
    val name: Map<Locale, String>,
    val accountId: UUID,
    val createdAt: Date
) : SurveyCreationCommand()
sealed class SurveyUpdateCommand : SurveyCommand(), UpdateCommand
data class Rename(override val aggregateId: UUID, val newName: String, val locale: Locale, val renamedAt: Date) : SurveyUpdateCommand()
data class Delete(override val aggregateId: UUID, val deletedAt: Date) : SurveyUpdateCommand()
data class Restore(override val aggregateId: UUID, val restoredAt: Date) : SurveyUpdateCommand()


sealed class SurveyEvent : Event
sealed class SurveyCreationEvent : SurveyEvent(), CreationEvent
data class Created(override val aggregateId: UUID, val name: Map<Locale, String>, val accountId: UUID, val createdAt: Date) : SurveyCreationEvent()
data class Snapshot(override val aggregateId: UUID, val name: Map<Locale, String>, val accountId: UUID, val deleted: Boolean, val snapshottedAt: Date) : SurveyCreationEvent()
sealed class SurveyUpdateEvent : SurveyEvent(), UpdateEvent
data class Renamed(override val aggregateId: UUID, val name: String, val locale: Locale, val namedAt: Date) : SurveyUpdateEvent()
data class Deleted(override val aggregateId: UUID, val deletedAt: Date) : SurveyUpdateEvent()
data class Restored(override val aggregateId: UUID, val restoredAt: Date) : SurveyUpdateEvent()

sealed class SurveyError : CommandError
object AlreadyRenamed : SurveyError()
object SurveyNameNotUnique : SurveyError()
object AlreadyDeleted : SurveyError()
object NotDeleted : SurveyError()

enum class Locale {
    en, de
}
