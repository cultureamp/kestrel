package survey.design

import java.util.*

class SurveyNamesProjector {
    fun handle(event: SurveyEvent): Unit = when (event) {
        is Created -> event.name.forEach { locale, name ->
            upsert(SurveyRow(event.aggregateId, event.accountId, locale, name))
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

    private fun upsert(surveyRow: SurveyRow): Unit {
        TODO("Insert in the database")
    }
}

data class SurveyRow(val aggregateId: UUID, val accountId: UUID, val locale: Locale, val name: String)



