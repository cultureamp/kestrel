package survey.design

import eventsourcing.ReadOnlyDatabase
import eventsourcing.ReadWriteDatabase
import java.util.UUID

class SurveyNamesCommandProjector(val database: ReadWriteDatabase) {
    fun project(event: SurveyEvent, aggregateId: UUID) = when (event) {
        is Created -> event.name.forEach { locale, name ->
            database.upsert(aggregateId, SurveyNameRow(event.accountId, locale, name))
        }
        is Renamed -> {
            val surveyRow = database.find(SurveyNameRow::class, aggregateId)!!
            database.upsert(aggregateId, surveyRow.copy(locale = event.locale, name = event.name))
        }
        is Deleted -> {
            database.delete(aggregateId)
        }
        is Restored -> Unit // chase up how this was resolved, especially since the old name might now be taken
    }
}

data class SurveyNameRow(val accountId: UUID, val locale: Locale, val name: String)

open class SurveyNamesCommandProjection(val readOnlyDatabase: ReadOnlyDatabase) {
    open fun nameExistsFor(accountId: UUID, name: String, locale: Locale): Boolean {
        return readOnlyDatabase.exists(SurveyNameRow::class, { it.locale == locale && it.name == name })
    }
}
