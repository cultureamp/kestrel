package survey.design

import com.cultureamp.eventsourcing.CommandGateway
import com.cultureamp.eventsourcing.Left
import com.cultureamp.eventsourcing.Right
import com.cultureamp.eventsourcing.Updated
import org.joda.time.DateTime
import java.util.*

class SurveySagaReactor(private val commandGateway: CommandGateway) {
    fun react(event: SurveySagaEvent, aggregateId: UUID) = when (event) {
        is SurveySagaStarted -> commandGateway.dispatch(StartCreatingSurvey(aggregateId, DateTime()))
        is StartedCreatingSurvey -> with(event) {
            val result = commandGateway.dispatch(command)
            when (result) {
                is Right -> commandGateway.dispatch(FinishCreatingSurvey(aggregateId, DateTime()))
                is Left -> commandGateway.dispatch(FailCreatingSurvey(aggregateId, result.error::class.simpleName!!, DateTime()))
            }
        }
        is FinishedCreatingSurvey -> commandGateway.dispatch(StartCreatingSurveyCaptureLayoutAggregate(aggregateId, DateTime()))
        is StartedCreatingSurveyCaptureLayoutAggregate -> with(event) {
            val result = commandGateway.dispatch(command)
            when (result) {
                is Right -> commandGateway.dispatch(FinishCreatingSurveyCaptureLayoutAggregate(aggregateId, DateTime()))
                is Left -> commandGateway.dispatch(FailCreatingSurveyCaptureLayoutAggregate(aggregateId, result.error::class.simpleName!!, DateTime()))
            }
        }
        is FinishedCreatingSurveyCaptureLayoutAggregate -> commandGateway.dispatch(FinishSurveySagaSuccessfully(aggregateId, DateTime()))
        is FailedCreatingSurvey -> commandGateway.dispatch(FinishSurveySagaUnsuccessfully(aggregateId, DateTime()))
        is FailedCreatingSurveyCaptureLayoutAggregate -> commandGateway.dispatch(StartRollbackCreatingSurvey(aggregateId, DateTime()))
        is StartedRollbackCreatingSurvey -> with(event) {
            val result = commandGateway.dispatch(command)
            when (result) {
                is Right -> commandGateway.dispatch(FinishRollbackCreatingSurvey(aggregateId, DateTime()))
                is Left -> commandGateway.dispatch(FailRollbackCreatingSurvey(aggregateId, result.error::class.simpleName!!, DateTime()))
            }
        }
        is FinishedRollbackCreatingSurvey -> commandGateway.dispatch(FinishSurveySagaUnsuccessfully(aggregateId, DateTime()))
        is FailedRollbackCreatingSurvey -> commandGateway.dispatch(FinishSurveySagaUnsuccessfully(aggregateId, DateTime()))
        is SurveySagaFinishedSuccessfully -> Right(Updated)
        is SurveySagaFinishedUnsuccessfully -> Right(Updated)
    }
}