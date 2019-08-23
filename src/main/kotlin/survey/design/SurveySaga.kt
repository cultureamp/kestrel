package survey.design

import eventsourcing.*
import java.util.*

data class SurveySaga(override val aggregateId: UUID, val startEvent: SurveySagaStarted, val updateEvents: List<SurveySagaUpdateEvent> = emptyList()) : AggregateWithProjection<Step, SurveySagaUpdateEvent, CommandError, CommandGateway, SurveySaga> {
    companion object : AggregateConstructorWithProjection<SurveySagaCreationCommand, SurveySagaCreationEvent, CommandError, Step, SurveySagaUpdateEvent, CommandGateway, SurveySaga> {

        override fun created(event: SurveySagaCreationEvent): SurveySaga = when (event) {
            is SurveySagaStarted -> SurveySaga(event.aggregateId, event)
        }

        override fun create(command: SurveySagaCreationCommand, commandGateway: CommandGateway): Either<CommandError, SurveySagaCreationEvent> = when (command) {
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

    override fun updated(event: SurveySagaUpdateEvent): SurveySaga {
        return this.copy(updateEvents = updateEvents + event)
    }

    override fun update(step: Step, commandGateway: CommandGateway): Either<CommandError, List<SurveySagaUpdateEvent>> {
        val lastEvent = updateEvents.lastOrNull() ?: startEvent
        return when (lastEvent) {
            is SurveySagaStarted -> with(lastEvent) {
                val createSurveyCommand = CreateSurvey(surveyAggregateId, surveyCaptureLayoutAggregateId, name, accountId, createdAt)
                Right.list(StartedCreatingSurvey(aggregateId, createSurveyCommand, Date()))
            }
            is StartedCreatingSurvey -> with(lastEvent) {
                val result = commandGateway.dispatch(command)
                when (result) {
                    is Right -> Right.list(FinishedCreatingSurvey(aggregateId, Date()))
                    is Left -> Right.list(FailedCreatingSurvey(aggregateId, result.error, Date()))
                }
            }
            is FinishedCreatingSurvey -> with(startEvent) {
                val generate = Generate(surveyCaptureLayoutAggregateId, surveyAggregateId, createdAt)
                Right.list(StartedCreatingSurveyCaptureLayoutAggregate(aggregateId, generate, Date()))
            }
            is StartedCreatingSurveyCaptureLayoutAggregate -> with(lastEvent) {
                val result = commandGateway.dispatch(command)
                when (result) {
                    is Right -> Right.list(FinishedCreatingSurveyCaptureLayoutAggregate(aggregateId, Date()))
                    is Left -> Right.list(FailedCreatingSurveyCaptureLayoutAggregate(aggregateId, result.error, Date()))
                }
            }
            is FinishedCreatingSurveyCaptureLayoutAggregate -> {
                Right.list(SurveySagaFinishedSuccessfully(aggregateId, Date()))
            }
            is FailedCreatingSurvey -> {
                Right.list(SurveySagaFinishedUnsuccessfully(aggregateId, Date()))
            }
            is FailedCreatingSurveyCaptureLayoutAggregate -> {
                val deleteSurvey = Delete(aggregateId, Date())
                Right.list(StartedRollbackCreatingSurvey(aggregateId, deleteSurvey, Date()))
            }

            is StartedRollbackCreatingSurvey -> with(lastEvent) {
                val result = commandGateway.dispatch(command)
                when (result) {
                    is Right -> Right.list(FinishedRollbackCreatingSurvey(aggregateId, Date()))
                    is Left -> Right.list(FailedRollbackCreatingSurvey(aggregateId, result.error, Date()))
                }
            }
            is FinishedRollbackCreatingSurvey -> Right.list(SurveySagaFinishedUnsuccessfully(aggregateId, Date()))
            is FailedRollbackCreatingSurvey -> Right.list(SurveySagaFinishedUnsuccessfully(aggregateId, Date()))
            is SurveySagaFinishedSuccessfully -> Right.list()
            is SurveySagaFinishedUnsuccessfully -> Right.list()
        }
    }
}

sealed class SurveySagaCreationCommand : CreationCommand
data class Create(
    override val aggregateId: UUID,
    val surveyCaptureLayoutAggregateId: UUID,
    val name: Map<Locale, String>,
    val accountId: UUID,
    val createdAt: Date
): SurveySagaCreationCommand()

sealed class SurveySagaEvent : Event
sealed class SurveySagaCreationEvent : SurveySagaEvent(), CreationEvent
data class SurveySagaStarted(
    override val aggregateId: UUID,
    val surveyAggregateId: UUID,
    val surveyCaptureLayoutAggregateId: UUID,
    val name: Map<Locale, String>,
    val accountId: UUID,
    val createdAt: Date,
    val startedAt: Date
) : SurveySagaCreationEvent()

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
