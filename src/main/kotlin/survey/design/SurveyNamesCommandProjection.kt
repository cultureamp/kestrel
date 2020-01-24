package survey.design

import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.*

class SurveyNamesCommandProjector internal constructor(private val database: Database) {
    fun project(event: SurveyEvent, aggregateId: UUID): Any = transaction(database) {
         when (event) {
            is Created -> event.name.forEach { locale, name ->
                SurveyNames.insert { // use insertIgnore for idempotency
                    it[SurveyNames.surveyId] = aggregateId
                    it[SurveyNames.accountId] = event.accountId
                    it[SurveyNames.locale] = locale.name
                    it[SurveyNames.name] = name
                }
            }
            is Renamed ->
                SurveyNames.update({ SurveyNames.surveyId eq aggregateId }) {
                    it[SurveyNames.locale] = event.locale.name
                    it[SurveyNames.name] = event.name
                }
            is Deleted ->
                SurveyNames.deleteWhere { SurveyNames.surveyId eq aggregateId }
            is Restored -> Unit // chase up how this was resolved, especially since the old name might now be taken
        }
    }
}

open class SurveyNamesQuery internal constructor(private val database: Database) {
    open fun nameExistsFor(accountId: UUID, name: String, locale: Locale) = transaction(database) {
        SurveyNames.select { (SurveyNames.name eq name) and (SurveyNames.locale eq locale.name) }.any()
    }
}

object SurveyNamesCommandProjection {
    fun create(database: Database): Pair<SurveyNamesQuery, SurveyNamesCommandProjector> {
        transaction(database) {
            SchemaUtils.create(SurveyNames)
        }
        return Pair(SurveyNamesQuery(database), SurveyNamesCommandProjector(database))
    }
}

object SurveyNames : Table() {
    val surveyId = SurveyNames.uuid("survey_id")
    val accountId = SurveyNames.uuid("account_id")
    val locale = SurveyNames.text("locale")
    val name = SurveyNames.text("name").index()
}