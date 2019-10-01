package survey.design

import eventsourcing.*
import java.util.*

data class SurveySagaAggregate(override val aggregateId: UUID,
                               val surveyAggregateId: UUID,
                               val surveyCaptureLayoutAggregateId: UUID,
                               val name: Map<Locale, String>,
                               val accountId: UUID,
                               val createdAt: Date) : Aggregate {
    constructor(event: SurveySagaStarted): this(
        event.aggregateId,
        event.surveyAggregateId,
        event.surveyCaptureLayoutAggregateId,
        event.name,
        event.accountId,
        event.createdAt
    )

    companion object {
        fun create(command: SurveySagaCreationCommand): Either<CommandError, SurveySagaStarted> = when (command) {
            is Create -> with(command) {
                val sagaId = UUID.randomUUID()
                val startEvent = SurveySagaStarted(
                    aggregateId = sagaId,
                    surveyAggregateId = aggregateId,
                    surveyCaptureLayoutAggregateId = surveyCaptureLayoutAggregateId,
                    name = name,
                    accountId = accountId,
                    createdAt = createdAt,
                    startedAt = Date()
                )
                Right(startEvent)
            }
        }
    }

    fun updated(event: SurveySagaUpdateEvent) = this

    fun update(command: SurveySagaUpdateCommand): Either<CommandError, List<SurveySagaUpdateEvent>> = Right.list(
        when (command) {
            is StartCreatingSurvey -> StartedCreatingSurvey(
                aggregateId,
                CreateSurvey(
                    surveyAggregateId,
                    surveyCaptureLayoutAggregateId,
                    name,
                    accountId,
                    createdAt
                ), command.startedAt
            )
            is FinishCreatingSurvey -> FinishedCreatingSurvey(aggregateId, command.finishedAt)
            is FailCreatingSurvey -> FailedCreatingSurvey(aggregateId, command.error, command.failedAt)
            is StartRollbackCreatingSurvey -> StartedRollbackCreatingSurvey(aggregateId, Delete(surveyAggregateId, command.startedAt), command.startedAt)
            is FinishRollbackCreatingSurvey -> FinishedRollbackCreatingSurvey(aggregateId, command.finishedAt)
            is FailRollbackCreatingSurvey -> FailedRollbackCreatingSurvey(aggregateId, command.error, command.rollbackFailedAt)
            is StartCreatingSurveyCaptureLayoutAggregate -> StartedCreatingSurveyCaptureLayoutAggregate(aggregateId, Generate(surveyCaptureLayoutAggregateId, surveyAggregateId, Date()), command.startedAt)
            is FinishCreatingSurveyCaptureLayoutAggregate -> FinishedCreatingSurveyCaptureLayoutAggregate(aggregateId, command.finishedAt)
            is FailCreatingSurveyCaptureLayoutAggregate -> FailedCreatingSurveyCaptureLayoutAggregate(aggregateId, command.error, command.failedAt)
            is FinishSurveySagaSuccessfully -> SurveySagaFinishedSuccessfully(aggregateId, command.finishedAt)
            is FinishSurveySagaUnsuccessfully -> SurveySagaFinishedUnsuccessfully(aggregateId, command.finishedAt)
        }
    )
}

sealed class SurveySagaCommand : Command
sealed class SurveySagaCreationCommand : SurveySagaCommand(), CreationCommand
data class Create(
    override val aggregateId: UUID,
    val surveyCaptureLayoutAggregateId: UUID,
    val name: Map<Locale, String>,
    val accountId: UUID,
    val createdAt: Date
): SurveySagaCreationCommand()

sealed class SurveySagaUpdateCommand : SurveySagaCommand(), UpdateCommand
data class StartCreatingSurvey(override val aggregateId: UUID, val startedAt: Date) : SurveySagaUpdateCommand()
data class FinishCreatingSurvey(override val aggregateId: UUID, val finishedAt: Date) : SurveySagaUpdateCommand()
data class FailCreatingSurvey(override val aggregateId: UUID, val error: CommandError, val failedAt: Date) : SurveySagaUpdateCommand()

data class StartRollbackCreatingSurvey(override val aggregateId: UUID, val startedAt: Date) : SurveySagaUpdateCommand()
data class FinishRollbackCreatingSurvey(override val aggregateId: UUID, val finishedAt: Date) : SurveySagaUpdateCommand()
data class FailRollbackCreatingSurvey(override val aggregateId: UUID, val error: CommandError, val rollbackFailedAt: Date) : SurveySagaUpdateCommand()

data class StartCreatingSurveyCaptureLayoutAggregate(override val aggregateId: UUID, val startedAt: Date) : SurveySagaUpdateCommand()
data class FinishCreatingSurveyCaptureLayoutAggregate(override val aggregateId: UUID, val finishedAt: Date) : SurveySagaUpdateCommand()
data class FailCreatingSurveyCaptureLayoutAggregate(override val aggregateId: UUID, val error: CommandError, val failedAt: Date) : SurveySagaUpdateCommand()

data class FinishSurveySagaSuccessfully(override val aggregateId: UUID, val finishedAt: Date) : SurveySagaUpdateCommand()
data class FinishSurveySagaUnsuccessfully(override val aggregateId: UUID, val finishedAt: Date) : SurveySagaUpdateCommand()


sealed class SurveySagaEvent : Event
data class SurveySagaStarted(
    override val aggregateId: UUID,
    val surveyAggregateId: UUID,
    val surveyCaptureLayoutAggregateId: UUID,
    val name: Map<Locale, String>,
    val accountId: UUID,
    val createdAt: Date,
    val startedAt: Date
) : SurveySagaEvent(), CreationEvent

sealed class SurveySagaUpdateEvent : SurveySagaEvent(), UpdateEvent

data class StartedCreatingSurvey(override val aggregateId: UUID, val command: CreateSurvey, val startedAt: Date) : SurveySagaUpdateEvent()
data class FinishedCreatingSurvey(override val aggregateId: UUID, val finishedAt: Date) : SurveySagaUpdateEvent()
data class FailedCreatingSurvey(override val aggregateId: UUID, val error: CommandError, val failedAt: Date) : SurveySagaUpdateEvent()

data class StartedRollbackCreatingSurvey(override val aggregateId: UUID, val command: Delete, val startedAt: Date) : SurveySagaUpdateEvent()
data class FinishedRollbackCreatingSurvey(override val aggregateId: UUID, val finishedAt: Date) : SurveySagaUpdateEvent()
data class FailedRollbackCreatingSurvey(override val aggregateId: UUID, val error: CommandError, val rollbackFailedAt: Date) : SurveySagaUpdateEvent()

data class StartedCreatingSurveyCaptureLayoutAggregate(override val aggregateId: UUID, val command: Generate, val startedAt: Date) : SurveySagaUpdateEvent()
data class FinishedCreatingSurveyCaptureLayoutAggregate(override val aggregateId: UUID, val finishedAt: Date) : SurveySagaUpdateEvent()
data class FailedCreatingSurveyCaptureLayoutAggregate(override val aggregateId: UUID, val error: CommandError, val failedAt: Date) : SurveySagaUpdateEvent()

data class SurveySagaFinishedSuccessfully(override val aggregateId: UUID, val finishedAt: Date) : SurveySagaUpdateEvent()
data class SurveySagaFinishedUnsuccessfully(override val aggregateId: UUID, val finishedAt: Date) : SurveySagaUpdateEvent()
