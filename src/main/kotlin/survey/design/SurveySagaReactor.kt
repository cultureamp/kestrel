package survey.design

import eventsourcing.*
import java.util.*

class SurveySagaReactor(private val commandGateway: CommandGateway) {
    fun react(event: SurveySagaEvent) = when (event) {
        is SurveySagaStarted -> commandGateway.dispatch(StartCreatingSurvey(event.aggregateId, Date()))
        is StartedCreatingSurvey -> with(event) {
            val result = commandGateway.dispatch(command)
            when (result) {
                is Right -> commandGateway.dispatch(FinishCreatingSurvey(aggregateId, Date()))
                is Left -> commandGateway.dispatch(FailCreatingSurvey(aggregateId, result.error, Date()))
            }
        }
        is FinishedCreatingSurvey -> commandGateway.dispatch(StartCreatingSurveyCaptureLayoutAggregate(event.aggregateId, Date()))
        is StartedCreatingSurveyCaptureLayoutAggregate -> with(event) {
            val result = commandGateway.dispatch(command)
            when (result) {
                is Right -> commandGateway.dispatch(FinishCreatingSurveyCaptureLayoutAggregate(aggregateId, Date()))
                is Left -> commandGateway.dispatch(FailCreatingSurveyCaptureLayoutAggregate(aggregateId, result.error, Date()))
            }
        }
        is FinishedCreatingSurveyCaptureLayoutAggregate -> commandGateway.dispatch(FinishSurveySagaSuccessfully(event.aggregateId, Date()))
        is FailedCreatingSurvey -> commandGateway.dispatch(FinishSurveySagaUnsuccessfully(event.aggregateId, Date()))
        is FailedCreatingSurveyCaptureLayoutAggregate -> commandGateway.dispatch(StartRollbackCreatingSurvey(event.aggregateId, Date()))
        is StartedRollbackCreatingSurvey -> with(event) {
            val result = commandGateway.dispatch(command)
            when (result) {
                is Right -> commandGateway.dispatch(FinishRollbackCreatingSurvey(event.aggregateId, Date()))
                is Left -> commandGateway.dispatch(FailRollbackCreatingSurvey(event.aggregateId, result.error, Date()))
            }
        }
        is FinishedRollbackCreatingSurvey -> commandGateway.dispatch(FinishSurveySagaUnsuccessfully(event.aggregateId, Date()))
        is FailedRollbackCreatingSurvey -> commandGateway.dispatch(FinishSurveySagaUnsuccessfully(event.aggregateId, Date()))
        is SurveySagaFinishedSuccessfully -> Right(Updated)
        is SurveySagaFinishedUnsuccessfully -> Right(Updated)
    }
}