package com.cultureamp.eventsourcing.example

import com.cultureamp.eventsourcing.*
import org.joda.time.DateTime
import com.cultureamp.eventsourcing.example.State.*
import java.util.*

data class ParticipantAggregate(val state: State) {
    companion object {
        @Suppress("UNUSED_PARAMETER")
        fun created(event: Invited): ParticipantAggregate = ParticipantAggregate(INVITED)

        fun create(command: Invite): Result<ParticipantError, Invited> = with(command) {
            Success(Invited(surveyPeriodId, employeeId, invitedAt))
        }
    }

    fun updated(event: ParticipantUpdateEvent): ParticipantAggregate = when(event) {
        is Invited -> this.copy(state = INVITED)
        is Uninvited -> this.copy(state = UNINVITED)
        is Reinvited -> this.copy(state = INVITED)
    }

    fun update(command: ParticipantUpdateCommand): Result<ParticipantError, List<ParticipantUpdateEvent>> =
        when (command) {
            is Invite -> when (state) {
                INVITED -> Failure(AlreadyInvitedException)
                UNINVITED -> with(command) { Success.list(Reinvited(invitedAt)) }
            }
            is Uninvite -> when (state) {
                UNINVITED -> Failure(AlreadyUninvitedException)
                INVITED -> Success.list(Uninvited(command.uninvitedAt))
            }
            is Reinvite -> Success.list(Reinvited(command.reinvitedAt))
        }

}

enum class State {
    INVITED, UNINVITED
}

sealed class ParticipantCommand : Command<ParticipantError>
sealed class ParticipantUpdateCommand : ParticipantCommand(), UpdateCommand<ParticipantError>
data class Invite(
    override val aggregateId: UUID,
    val surveyPeriodId: UUID,
    val employeeId: UUID,
    val invitedAt: DateTime
) : ParticipantUpdateCommand(), CreationCommand<ParticipantError>

data class Uninvite(override val aggregateId: UUID, val uninvitedAt: DateTime) : ParticipantUpdateCommand()
data class Reinvite(override val aggregateId: UUID, val reinvitedAt: DateTime) : ParticipantUpdateCommand()

sealed class ParticipantEvent : DomainEvent
sealed class ParticipantUpdateEvent : ParticipantEvent(), UpdateEvent
data class Invited(val surveyPeriodId: UUID, val employeeId: UUID, val invitedAt: DateTime) : ParticipantUpdateEvent(),
    CreationEvent

data class Uninvited(val uninvitedAt: DateTime) : ParticipantUpdateEvent()
data class Reinvited(val reinvitedAt: DateTime) : ParticipantUpdateEvent()

sealed class ParticipantError : DomainError
object AlreadyInvitedException : ParticipantError(), AlreadyActionedCommandError
object AlreadyUninvitedException : ParticipantError(), AlreadyActionedCommandError