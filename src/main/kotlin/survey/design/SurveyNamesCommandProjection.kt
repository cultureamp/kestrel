package survey.design

import eventsourcing.Projector
import eventsourcing.ReadOnlyDatabase
import eventsourcing.ReadWriteDatabase
import java.util.UUID

class SurveyNamesCommandProjector(val database: ReadWriteDatabase) : Projector<SurveyEvent> {
    override fun project(event: SurveyEvent, aggregateId: UUID) = when (event) {
        is Created -> event.name.forEach { locale, name ->
            database.upsert(aggregateId, SurveyRow(event.accountId, locale, name))
        }
        is Renamed -> {
            val surveyRow = database.find(SurveyRow::class, aggregateId)
            database.upsert(aggregateId, surveyRow.copy(locale = event.locale, name = event.name))
        }
        is Deleted -> {
            database.delete(aggregateId)
        }
        is Restored -> Unit // chase up how this was resolved, especially since the old name might now be taken
    }
}

data class SurveyRow(val accountId: UUID, val locale: Locale, val name: String)

open class SurveyNamesCommandProjection(val readOnlyDatabase: ReadOnlyDatabase) {
    open fun nameExistsFor(accountId: UUID, name: String, locale: Locale): Boolean {
        return readOnlyDatabase.exists(SurveyRow::class, { it.locale == locale && it.name == name })
    }
}
