package survey.design

import eventsourcing.Projector
import eventsourcing.ReadOnlyDatabase
import eventsourcing.ReadWriteDatabase
import java.util.UUID

class SurveyNamesProjector(database: ReadWriteDatabase) : Projector<SurveyEvent> {
    override fun project(event: SurveyEvent) = when (event) {
        is Created -> event.name.forEach { locale, name ->
            //upsert(SurveyRow(event.aggregateId, event.accountId, locale, name))
        }
        is Renamed -> {
            upsert(find(event.aggregateId).copy(locale = event.locale, name = event.name))
        }
        is Snapshot -> TODO()
        is Deleted -> TODO()
        is Restored -> TODO()
    }

    private fun find(aggregateId: UUID): SurveyRow {
        TODO("Find in the database")
    }

    private fun upsert(surveyRow: SurveyRow) {
        TODO("Insert in the database")
    }
}

data class SurveyRow(val aggregateId: UUID, val accountId: UUID, val locale: Locale, val name: String)

open class SurveyNamesProjection(database: ReadOnlyDatabase) {
    open fun nameExistsFor(accountId: UUID, name: String, locale: Locale): Boolean {
        TODO("Find in the database")
    }
}

object StubSurveyNamesProjection : SurveyNamesProjection(object:ReadOnlyDatabase {}) {
    override fun nameExistsFor(accountId: UUID, name: String, locale: Locale): Boolean = false
}
