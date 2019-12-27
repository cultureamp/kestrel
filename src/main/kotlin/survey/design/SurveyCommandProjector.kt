package survey.design

import eventsourcing.ReadOnlyDatabase
import eventsourcing.ReadWriteDatabase
import java.lang.RuntimeException
import java.util.UUID

class SurveyCommandProjector(val database: ReadWriteDatabase) {

    fun first(event: Created, aggregateId: UUID) {
        database.upsert(aggregateId, SurveyToCaptureLayoutRow(event.accountId, aggregateId, null))
    }

    fun second(event: Generated, aggregateId: UUID) {
        database.find(SurveyToCaptureLayoutRow::class, event.surveyId)?.let { row ->
            database.upsert(aggregateId, row.copy(surveyCaptureLayoutId = aggregateId))
        } ?: throw RuntimeException("Survey not found") // this is an anti-pattern, don't throw in projectors
    }
}

class SurveyCommandProjection(val database: ReadOnlyDatabase) {
    fun find(surveyCaptureLayoutId: UUID) =
        database.findBy(SurveyToCaptureLayoutRow::class, { it.surveyCaptureLayoutId == surveyCaptureLayoutId })
}

data class SurveyToCaptureLayoutRow(val accountId: UUID, val surveyId: UUID, val surveyCaptureLayoutId: UUID?)
