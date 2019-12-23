package survey.design

import eventsourcing.*
import java.util.*

class SurveySagaReactor(private val commandGateway: CommandGateway) {
    fun react(event: SurveySagaEvent, aggregateId: UUID) = when (event) {
        is SurveySagaStarted -> commandGateway.dispatch(StartCreatingSurvey(aggregateId, Date()))
        is StartedCreatingSurvey -> with(event) {
            val result = commandGateway.dispatch(command)
            when (result) {
                is Right -> commandGateway.dispatch(FinishCreatingSurvey(aggregateId, Date()))
                is Left -> commandGateway.dispatch(FailCreatingSurvey(aggregateId, result.error, Date()))
            }
        }
        is FinishedCreatingSurvey -> commandGateway.dispatch(StartCreatingSurveyCaptureLayoutAggregate(aggregateId, Date()))
        is StartedCreatingSurveyCaptureLayoutAggregate -> with(event) {
            val result = commandGateway.dispatch(command)
            when (result) {
                is Right -> commandGateway.dispatch(FinishCreatingSurveyCaptureLayoutAggregate(aggregateId, Date()))
                is Left -> commandGateway.dispatch(FailCreatingSurveyCaptureLayoutAggregate(aggregateId, result.error, Date()))
            }
        }
        is FinishedCreatingSurveyCaptureLayoutAggregate -> commandGateway.dispatch(FinishSurveySagaSuccessfully(aggregateId, Date()))
        is FailedCreatingSurvey -> commandGateway.dispatch(FinishSurveySagaUnsuccessfully(aggregateId, Date()))
        is FailedCreatingSurveyCaptureLayoutAggregate -> commandGateway.dispatch(StartRollbackCreatingSurvey(aggregateId, Date()))
        is StartedRollbackCreatingSurvey -> with(event) {
            val result = commandGateway.dispatch(command)
            when (result) {
                is Right -> commandGateway.dispatch(FinishRollbackCreatingSurvey(aggregateId, Date()))
                is Left -> commandGateway.dispatch(FailRollbackCreatingSurvey(aggregateId, result.error, Date()))
            }
        }
        is FinishedRollbackCreatingSurvey -> commandGateway.dispatch(FinishSurveySagaUnsuccessfully(aggregateId, Date()))
        is FailedRollbackCreatingSurvey -> commandGateway.dispatch(FinishSurveySagaUnsuccessfully(aggregateId, Date()))
        is SurveySagaFinishedSuccessfully -> Right(Updated)
        is SurveySagaFinishedUnsuccessfully -> Right(Updated)
    }
}