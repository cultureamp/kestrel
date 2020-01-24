package survey.design

import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.*

class SurveyCommandProjector internal constructor(private val database: Database) {

    fun first(event: Created, aggregateId: UUID) = transaction(database) {
        // use insertIgnore for idempotency
        Surveys.insert {
            it[Surveys.accountId] = event.accountId
            it[Surveys.surveyId] = aggregateId
        }
    }

    fun second(event: Generated, aggregateId: UUID) = transaction(database) {
        val rowsUpdated = Surveys.update({ Surveys.surveyId eq event.surveyId }) {
            it[Surveys.surveyCaptureLayoutId] = aggregateId
        }

        // this is an anti-pattern, don't throw in projectors
        if (rowsUpdated < 1) {
            throw RuntimeException("Survey not found")
        }
    }
}

class SurveyQuery internal constructor(private val database: Database) {
    fun bySurvey(surveyId: UUID): Survey? = transaction(database) {
        Surveys.select { Surveys.surveyId eq surveyId }.limit(1).toList().map { row ->
            Survey(
                accountId = row[Surveys.accountId],
                surveyId = row[Surveys.surveyId],
                surveyCaptureLayoutId = row[Surveys.surveyCaptureLayoutId]
            )
        }.firstOrNull()
    }
}

data class Survey(val accountId: UUID, val surveyId: UUID, val surveyCaptureLayoutId: UUID? = null)

object SurveyCommandProjection {
    fun create(database: Database): Pair<SurveyQuery, SurveyCommandProjector> {
        transaction(database) {
            SchemaUtils.create(Surveys)
        }
        return Pair(SurveyQuery(database), SurveyCommandProjector(database))
    }
}

object Surveys : Table() {
    val accountId = Surveys.uuid("account_id")
    val surveyId = Surveys.uuid("survey_id")
    val surveyCaptureLayoutId = Surveys.uuid("survey_capture_layout_id").nullable()
}