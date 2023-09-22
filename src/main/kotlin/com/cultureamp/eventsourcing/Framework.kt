package com.cultureamp.eventsourcing

import org.joda.time.DateTime
import java.util.*

interface Command {
    val aggregateId: UUID
}

interface CreationCommand : Command {
    override val aggregateId: UUID
}

interface UpdateCommand : Command {
    override val aggregateId: UUID
}

data class Event<M: EventMetadata> (
    val id: UUID,
    val aggregateId: UUID,
    val aggregateSequence: Long,
    val aggregateType: String,
    val createdAt: DateTime,
    val metadata: M,
    val domainEvent: DomainEvent
)

data class SequencedEvent<M: EventMetadata>(val event: Event<M>, val sequence: Long)

open class EventMetadata

/**
 * Standard Culture Amp metadata. You probably want to consider logging these fields (but note they are optional).
 * If you know these fields at all times, you can probably create a new `Metadata` subclass.
 *
 * @property accountId: The aggregate ID of the account that owns the event’s aggregate instance
 * @property executorId: The aggregate ID of the user that executed the command that resulted in the event
 * @property causationId: If the event is the result of an action performed by a reactor, the ID of the causal event
 * @property correlationId: The identifier of a correlated action, request or event
 */
data class CAStandardMetadata(
    val accountId: UUID? = null,
    val executorId: UUID? = null,
    val correlationId: UUID? = null,
    val causationId: UUID? = null
): EventMetadata()

interface DomainEvent

interface CreationEvent : DomainEvent

interface UpdateEvent : DomainEvent

interface CommandError

interface DomainError: CommandError

interface AlreadyActionedCommandError : DomainError

interface AuthorizationCommandError : CommandError

interface RetriableError : CommandError
