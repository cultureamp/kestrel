package survey.design

import eventsourcing.*
import org.joda.time.DateTime
import java.util.*

data class SurveySagaAggregate(val surveyAggregateId: UUID,
                               val surveyCaptureLayoutAggregateId: UUID,
                               val name: Map<Locale, String>,
                               val accountId: UUID,
                               val createdAt: DateTime) : Aggregate {
    constructor(event: SurveySagaStarted): this(
        event.surveyAggregateId,
        event.surveyCaptureLayoutAggregateId,
        event.name,
        event.accountId,
        event.createdAt
    )

    companion object {
        fun create(command: SurveySagaCreationCommand): Either<CommandError, SurveySagaStarted> = when (command) {
            is Create -> with(command) {
                val startEvent = SurveySagaStarted(
                    surveyAggregateId = surveyAggregateId,
                    surveyCaptureLayoutAggregateId = surveyCaptureLayoutAggregateId,
                    name = name,
                    accountId = accountId,
                    createdAt = createdAt,
                    startedAt = DateTime()
                )
                Right(startEvent)
            }
        }
    }

    fun update(command: SurveySagaUpdateCommand): Either<CommandError, List<SurveySagaUpdateEvent>> = Right.list(
        when (command) {
            is StartCreatingSurvey -> StartedCreatingSurvey(
                CreateSurvey(
                    surveyAggregateId,
                    surveyCaptureLayoutAggregateId,
                    name,
                    accountId,
                    createdAt
                ), command.startedAt
            )
            is FinishCreatingSurvey -> FinishedCreatingSurvey(command.finishedAt)
            is FailCreatingSurvey -> FailedCreatingSurvey(command.errorType, command.failedAt)
            is StartRollbackCreatingSurvey -> StartedRollbackCreatingSurvey(Delete(surveyAggregateId, command.startedAt), command.startedAt)
            is FinishRollbackCreatingSurvey -> FinishedRollbackCreatingSurvey(command.finishedAt)
            is FailRollbackCreatingSurvey -> FailedRollbackCreatingSurvey(command.errorType, command.rollbackFailedAt)
            is StartCreatingSurveyCaptureLayoutAggregate -> StartedCreatingSurveyCaptureLayoutAggregate(Generate(surveyCaptureLayoutAggregateId, surveyAggregateId, DateTime()), command.startedAt)
            is FinishCreatingSurveyCaptureLayoutAggregate -> FinishedCreatingSurveyCaptureLayoutAggregate(command.finishedAt)
            is FailCreatingSurveyCaptureLayoutAggregate -> FailedCreatingSurveyCaptureLayoutAggregate(command.errorType, command.failedAt)
            is FinishSurveySagaSuccessfully -> SurveySagaFinishedSuccessfully(command.finishedAt)
            is FinishSurveySagaUnsuccessfully -> SurveySagaFinishedUnsuccessfully(command.finishedAt)
        }
    )
}

sealed class SurveySagaCommand : Command
sealed class SurveySagaCreationCommand : SurveySagaCommand(), CreationCommand
data class Create(
    override val aggregateId: UUID,
    val surveyAggregateId: UUID,
    val surveyCaptureLayoutAggregateId: UUID,
    val name: Map<Locale, String>,
    val accountId: UUID,
    val createdAt: DateTime
): SurveySagaCreationCommand()

sealed class SurveySagaUpdateCommand : SurveySagaCommand(), UpdateCommand
data class StartCreatingSurvey(override val aggregateId: UUID, val startedAt: DateTime) : SurveySagaUpdateCommand()
data class FinishCreatingSurvey(override val aggregateId: UUID, val finishedAt: DateTime) : SurveySagaUpdateCommand()
data class FailCreatingSurvey(override val aggregateId: UUID, val errorType: String, val failedAt: DateTime) : SurveySagaUpdateCommand()

data class StartRollbackCreatingSurvey(override val aggregateId: UUID, val startedAt: DateTime) : SurveySagaUpdateCommand()
data class FinishRollbackCreatingSurvey(override val aggregateId: UUID, val finishedAt: DateTime) : SurveySagaUpdateCommand()
data class FailRollbackCreatingSurvey(override val aggregateId: UUID, val errorType: String, val rollbackFailedAt: DateTime) : SurveySagaUpdateCommand()

data class StartCreatingSurveyCaptureLayoutAggregate(override val aggregateId: UUID, val startedAt: DateTime) : SurveySagaUpdateCommand()
data class FinishCreatingSurveyCaptureLayoutAggregate(override val aggregateId: UUID, val finishedAt: DateTime) : SurveySagaUpdateCommand()
data class FailCreatingSurveyCaptureLayoutAggregate(override val aggregateId: UUID, val errorType: String, val failedAt: DateTime) : SurveySagaUpdateCommand()

data class FinishSurveySagaSuccessfully(override val aggregateId: UUID, val finishedAt: DateTime) : SurveySagaUpdateCommand()
data class FinishSurveySagaUnsuccessfully(override val aggregateId: UUID, val finishedAt: DateTime) : SurveySagaUpdateCommand()


sealed class SurveySagaEvent : DomainEvent
data class SurveySagaStarted(
    val surveyAggregateId: UUID,
    val surveyCaptureLayoutAggregateId: UUID,
    val name: Map<Locale, String>,
    val accountId: UUID,
    val createdAt: DateTime,
    val startedAt: DateTime
) : SurveySagaEvent(), CreationEvent

sealed class SurveySagaUpdateEvent : SurveySagaEvent(), UpdateEvent

data class StartedCreatingSurvey(val command: CreateSurvey, val startedAt: DateTime) : SurveySagaUpdateEvent()
data class FinishedCreatingSurvey(val finishedAt: DateTime) : SurveySagaUpdateEvent()
data class FailedCreatingSurvey(val errorType: String, val failedAt: DateTime) : SurveySagaUpdateEvent() // this can't be rehydrated

data class StartedRollbackCreatingSurvey(val command: Delete, val startedAt: DateTime) : SurveySagaUpdateEvent()
data class FinishedRollbackCreatingSurvey(val finishedAt: DateTime) : SurveySagaUpdateEvent()
data class FailedRollbackCreatingSurvey(val errorType: String, val rollbackFailedAt: DateTime) : SurveySagaUpdateEvent()

data class StartedCreatingSurveyCaptureLayoutAggregate(val command: Generate, val startedAt: DateTime) : SurveySagaUpdateEvent()
data class FinishedCreatingSurveyCaptureLayoutAggregate(val finishedAt: DateTime) : SurveySagaUpdateEvent()
data class FailedCreatingSurveyCaptureLayoutAggregate(val errorType: String, val failedAt: DateTime) : SurveySagaUpdateEvent()

data class SurveySagaFinishedSuccessfully(val finishedAt: DateTime) : SurveySagaUpdateEvent()
data class SurveySagaFinishedUnsuccessfully(val finishedAt: DateTime) : SurveySagaUpdateEvent()
