package survey.design

import eventsourcing.Projector
import eventsourcing.ReadOnlyDatabase
import eventsourcing.ReadWriteDatabase
import java.util.UUID

class SurveyNamesProjector(val database: ReadWriteDatabase) : Projector<SurveyEvent> {
    override fun project(event: SurveyEvent) = when (event) {
        is Created -> event.name.forEach { locale, name ->
            database.upsert(event.aggregateId, SurveyRow(event.accountId, locale, name))
        }
        is Renamed -> {
            val surveyRow = database.find(SurveyRow::class, event.aggregateId)
            database.upsert(event.aggregateId, surveyRow.copy(locale = event.locale, name = event.name))
        }
        is Snapshot -> event.name.forEach { locale, name ->
            database.upsert(event.aggregateId, SurveyRow(event.accountId, locale, name))
        }
        is Deleted -> {
            database.delete(event.aggregateId)
        }
        is Restored -> Unit // chase up how this was resolved, especially since the old name might now be taken
    }
}

data class SurveyRow(val accountId: UUID, val locale: Locale, val name: String)

open class SurveyNamesProjection(database: ReadOnlyDatabase) {
    open fun nameExistsFor(accountId: UUID, name: String, locale: Locale): Boolean {
        TODO("Find in the database")
    }
}

object StubSurveyNamesProjection : SurveyNamesProjection(object:ReadOnlyDatabase {}) {
    override fun nameExistsFor(accountId: UUID, name: String, locale: Locale): Boolean = false
}
