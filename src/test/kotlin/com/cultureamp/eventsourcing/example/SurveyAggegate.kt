package com.cultureamp.eventsourcing.example

import com.cultureamp.eventsourcing.*
import org.joda.time.DateTime
import java.util.*

data class SurveyAggregate(val name: Map<Locale, String>, val accountId: UUID, val deleted: Boolean = false) {
    constructor(event: Created): this(event.name, event.accountId)

    companion object {
        fun create(query: SurveyNamesQuery, command: SurveyCreationCommand): Result<SurveyError, Created> {
            return when (command) {
                is CreateSurvey -> when {
                    command.name.any { (locale, name) -> query.nameExistsFor(command.accountId, name, locale)} -> Failure(SurveyNameNotUnique)
                    else -> Success(Created(command.name, command.accountId, command.createdAt))
                }
            }
        }
    }

    fun updated(event: SurveyUpdateEvent): SurveyAggregate = when (event) {
        is Renamed -> this.copy(name = name + (event.locale to event.name))
        is Deleted -> this.copy(deleted = true)
        is Restored -> this.copy(deleted = false)
    }

    fun update(query: SurveyNamesQuery, command: SurveyUpdateCommand): Result<SurveyError, List<SurveyUpdateEvent>> = when (command) {
        is Rename -> when {
            name.get(command.locale) == command.newName -> Failure(AlreadyRenamed)
            query.nameExistsFor(accountId, command.newName, command.locale) -> Failure(SurveyNameNotUnique)
            else -> Success.list(Renamed(command.newName, command.locale, command.renamedAt))
        }
        is Delete -> when (deleted) {
            true -> Failure(AlreadyDeleted)
            false -> Success.list(Deleted(command.deletedAt))
        }
        is Restore -> when (deleted) {
            true -> Success.list(Restored(command.restoredAt))
            false -> Failure(NotDeleted)
        }
    }
}

sealed class SurveyCommand : Command<SurveyError>
sealed class SurveyCreationCommand : SurveyCommand(), CreationCommand<SurveyError>
data class CreateSurvey(
    override val aggregateId: UUID,
    val surveyCaptureLayoutAggregateId: UUID,
    val name: Map<Locale, String>,
    val accountId: UUID,
    val createdAt: DateTime
) : SurveyCreationCommand()
sealed class SurveyUpdateCommand : SurveyCommand(), UpdateCommand<SurveyError>
data class Rename(override val aggregateId: UUID, val newName: String, val locale: Locale, val renamedAt: DateTime) : SurveyUpdateCommand()
data class Delete(override val aggregateId: UUID, val deletedAt: DateTime) : SurveyUpdateCommand()
data class Restore(override val aggregateId: UUID, val restoredAt: DateTime) : SurveyUpdateCommand()


sealed class SurveyEvent : DomainEvent
data class Created(val name: Map<Locale, String>, val accountId: UUID, val createdAt: DateTime) : SurveyEvent(), CreationEvent
sealed class SurveyUpdateEvent : SurveyEvent(), UpdateEvent
data class Renamed(val name: String, val locale: Locale, val namedAt: DateTime) : SurveyUpdateEvent()
data class Deleted(val deletedAt: DateTime) : SurveyUpdateEvent()
data class Restored(val restoredAt: DateTime) : SurveyUpdateEvent()

sealed class SurveyError : DomainError
object SurveyNameNotUnique : SurveyError()
object AlreadyRenamed : SurveyError(), AlreadyActionedCommandError
object AlreadyDeleted : SurveyError(), AlreadyActionedCommandError
object NotDeleted : SurveyError(), AlreadyActionedCommandError

enum class Locale {
    en, de
}