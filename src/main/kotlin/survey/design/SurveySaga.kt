package survey.design

import eventsourcing.*
import java.util.*

data class SurveySaga(override val aggregateId: UUID) : AggregateWithProjection<Nothing, SurveySagaUpdateEvent, SagaCannotBeUpdated, CommandGateway, SurveySaga> {
    companion object : AggregateConstructorWithProjection<SurveySagaCreationCommand, SurveySagaCreationEvent, SagaCannotBeUpdated, Nothing, SurveySagaUpdateEvent, CommandGateway, SurveySaga> {

        override fun created(event: SurveySagaCreationEvent): SurveySaga = when (event) {
            is SurveySagaStarted -> SurveySaga(event.aggregateId)
        }

        // TODO this doesn't save state part of the way through so isn't resumeable
        override fun create(command: SurveySagaCreationCommand, commandGateway: CommandGateway): Either<SagaCannotBeUpdated, Pair<SurveySagaCreationEvent, List<SurveySagaUpdateEvent>>> = when (command) {
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

                val updateEvents = mutableListOf<SurveySagaUpdateEvent>()

                val createSurveyCommand = CreateSurvey(
                    aggregateId = aggregateId,
                    surveyCaptureLayoutAggregateId = surveyCaptureLayoutAggregateId,
                    name = name,
                    accountId = accountId,
                    createdAt = createdAt
                )

                updateEvents.add(StartedCreatingSurvey(sagaId, createSurveyCommand, Date()))

                val surveyCreationResult = commandGateway.dispatch(createSurveyCommand)
                val surveyEvent = when (surveyCreationResult) {
                    is Right -> FinishedCreatingSurvey(sagaId, Date())
                    is Left -> FailedCreatingSurvey(sagaId, surveyCreationResult.error, Date())
                }
                updateEvents.add(surveyEvent)

                if (surveyEvent is FinishedCreatingSurvey) {
                    val generate = Generate(
                        aggregateId = surveyCaptureLayoutAggregateId,
                        surveyId = aggregateId,
                        generatedAt = createdAt
                    )

                    updateEvents.add(StartedCreatingSurveyCaptureLayoutAggregate(sagaId, generate, Date()))

                    when (commandGateway.dispatch(generate)) {
                        is Right -> {
                            updateEvents.add(FinishedCreatingSurveyCaptureLayoutAggregate(sagaId, Date()))
                        }
                        is Left -> {
                            val deleteSurvey = Delete(aggregateId, Date())
                            updateEvents.add(FailedCreatingSurveyCaptureLayoutAggregate(sagaId, Date()))
                            updateEvents.add(StartedRollbackCreatingSurvey(sagaId, deleteSurvey, Date()))
                            val deleteSurveyResult = commandGateway.dispatch(deleteSurvey)
                            when (deleteSurveyResult) {
                                is Right -> {
                                    updateEvents.add(FinishedRollbackCreatingSurvey(sagaId, Date()))
                                }
                                is Left -> {
                                    updateEvents.add(FailedRollbackCreatingSurvey(sagaId, deleteSurveyResult.error, Date()))
                                }
                            }
                        }
                    }
                }
                updateEvents.add(SurveySagaFinished(sagaId, Date()))
                Right(Pair(startEvent, updateEvents))
            }
        }
    }

    override fun updated(event: SurveySagaUpdateEvent): SurveySaga {
        return this // this would be used if Sagas were resumeable
    }

    override fun update(command: Nothing, projection: CommandGateway): Either<SagaCannotBeUpdated, List<SurveySagaUpdateEvent>> {
        return Left(SagaCannotBeUpdated) // this would be used if the Saga could be cancelled
    }
}

object SagaCannotBeUpdated : CommandError

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
data class FailedCreatingSurveyCaptureLayoutAggregate(override val aggregateId: UUID, val failedAt: Date) : SurveySagaUpdateEvent()

data class SurveySagaFinished(override val aggregateId: UUID, val finishedAt: Date) : SurveySagaUpdateEvent()
