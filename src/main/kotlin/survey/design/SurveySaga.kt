package survey.design

import eventsourcing.*
import io.ktor.http.HttpStatusCode
import java.util.*

data class SurveySaga(override val aggregateId: UUID) : AggregateWithProjection<Nothing, SurveySagaUpdateEvent, SagasCantBeUpdatedError, CommandGateway, SurveySaga> {
    companion object : AggregateConstructorWithProjection<SurveySagaCreationCommand, SurveySagaCreationEvent, SagasCantBeUpdatedError, Nothing, SurveySagaUpdateEvent, CommandGateway, SurveySaga> {

        override fun created(event: SurveySagaCreationEvent): SurveySaga = when (event) {
            is SurveySagaCreated -> SurveySaga(event.aggregateId)
        }

        // TODO this doesn't save state part of the way through so isn't resumeable
        override fun create(command: SurveySagaCreationCommand, commandGateway: CommandGateway): Either<SagasCantBeUpdatedError, Pair<SurveySagaCreationEvent, List<SurveySagaUpdateEvent>>> = when (command) {
            is Create -> with(command) {
                val sagaId = UUID.randomUUID()
                val startEvent = SurveySagaCreated(sagaId, Date())

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
                    HttpStatusCode.Created -> FinishedCreatingSurvey(sagaId, Date())
                    else -> FailedCreatingSurvey(sagaId, Date())
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
                        HttpStatusCode.Created -> {
                            updateEvents.add(FinishedCreatingSurveyCaptureLayoutAggregate(sagaId, Date()))
                        }
                        else -> {
                            val deleteSurvey = Delete(aggregateId, Date())
                            updateEvents.add(RollbackCreatingSurvey(sagaId, deleteSurvey, Date()))
                            when (commandGateway.dispatch(deleteSurvey)) {
                                HttpStatusCode.OK -> {
                                }
                                else -> {
                                    updateEvents.add(RollbackCreatingSurveyFailed(sagaId, Date()))
                                }
                            }
                            updateEvents.add(FailedCreatingSurveyCaptureLayoutAggregate(sagaId, Date()))
                        }
                    }
                }
                updateEvents.add(SurveySagaFinished(sagaId, Date()))
                Right(Pair(startEvent, updateEvents))
            }
        }
    }

    override fun updated(event: SurveySagaUpdateEvent): SurveySaga {
        return this
    }

    override fun update(command: Nothing, projection: CommandGateway): Either<SagasCantBeUpdatedError, List<SurveySagaUpdateEvent>> {
        return Left(SagasCantBeUpdatedError)
    }
}

object SagasCantBeUpdatedError : CommandError

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
data class SurveySagaCreated(override val aggregateId: UUID, val createdAt: Date) : SurveySagaCreationEvent()

sealed class SurveySagaUpdateEvent : SurveySagaEvent(), UpdateEvent

data class StartedCreatingSurvey(override val aggregateId: UUID, val command: CreateSurvey, val startedAt: Date) : SurveySagaUpdateEvent()
data class FinishedCreatingSurvey(override val aggregateId: UUID, val finishedAt: Date) : SurveySagaUpdateEvent()
data class FailedCreatingSurvey(override val aggregateId: UUID, val failedAt: Date) : SurveySagaUpdateEvent()
data class RollbackCreatingSurvey(override val aggregateId: UUID, val command: Delete, val rolledBackAt: Date) : SurveySagaUpdateEvent()
data class RollbackCreatingSurveyFailed(override val aggregateId: UUID, val rollbackFailedAt: Date) : SurveySagaUpdateEvent()

data class StartedCreatingSurveyCaptureLayoutAggregate(override val aggregateId: UUID, val command: Generate, val startedAt: Date) : SurveySagaUpdateEvent()
data class FinishedCreatingSurveyCaptureLayoutAggregate(override val aggregateId: UUID, val finishedAt: Date) : SurveySagaUpdateEvent()
data class FailedCreatingSurveyCaptureLayoutAggregate(override val aggregateId: UUID, val failedAt: Date) : SurveySagaUpdateEvent()

data class SurveySagaFinished(override val aggregateId: UUID, val finishedAt: Date) : SurveySagaUpdateEvent()
