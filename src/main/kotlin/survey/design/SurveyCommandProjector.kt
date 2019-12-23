package survey.design

import eventsourcing.DoubleProjector
import java.util.UUID

class SurveyCommandProjector : DoubleProjector<SurveyEvent, SurveyCaptureLayoutEvent> {
    override fun first(event: SurveyEvent, aggregateId: UUID) {
        TODO("not implemented")
    }

    override fun second(event: SurveyCaptureLayoutEvent, aggregateId: UUID) {
        TODO("not implemented")
    }
}