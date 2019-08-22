package survey.design

import eventsourcing.*
import java.util.*

data class SurveySaga(override val aggregateId: UUID) : AggregateWithProjection<Nothing, SurveySagaUpdateEvent, CommandError, CommandGateway, SurveySaga> {
    companion object : AggregateConstructorWithProjection<SurveySagaCreationCommand, SurveySagaCreationEvent, CommandError, Nothing, SurveySagaUpdateEvent, CommandGateway, SurveySaga> {
        override fun created(event: SurveySagaCreationEvent): SurveySaga {
            TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
        }

        override fun create(command: SurveySagaCreationCommand, projection: CommandGateway): Either<CommandError, SurveySagaCreationEvent> {
            TODO("not implemented")
        }
    }

    override fun updated(event: SurveySagaUpdateEvent): SurveySaga {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun update(
        command: Nothing,
        projection: CommandGateway
    ): Either<CommandError, List<SurveySagaUpdateEvent>> {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
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
data class SurveySagaCreated(override val aggregateId: UUID, val createdAt: Date) : SurveySagaCreationEvent()

sealed class SurveySagaUpdateEvent : SurveySagaEvent(), UpdateEvent
data class StartedCreatingSurvey(override val aggregateId: UUID, val surveyAggregateId: UUID, val name: Map<Locale, String>, val accountId: UUID, val createdAt: Date, val startedAt: Date) : SurveySagaUpdateEvent()
data class FinishedCreatingSurvey(override val aggregateId: UUID, val finishedAt: Date) : SurveySagaUpdateEvent()
data class StartedCreatingSurveyCaptureLayoutAggregate(override val aggregateId: UUID, val surveyCaptureLayoutAggregateId: UUID, val name: Map<Locale, String>, val accountId: UUID, val createdAt: Date, val startedAt: Date) : SurveySagaUpdateEvent()
data class FinishedCreatingSurveyCaptureLayoutAggregate(override val aggregateId: UUID, val finishedAt: Date) : SurveySagaUpdateEvent()
data class SurveySagaFinished(override val aggregateId: UUID, val finishedAt: Date) : SurveySagaUpdateEvent()
