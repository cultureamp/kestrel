package survey.design

import eventsourcing.Projector
import java.util.*

class SurveyNamesProjector : Projector<SurveyEvent> {
    override fun handle(event: SurveyEvent) = when (event) {
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

    private fun upsert(surveyRow: SurveyRow) {
        TODO("Insert in the database")
    }
}

data class SurveyRow(val aggregateId: UUID, val accountId: UUID, val locale: Locale, val name: String)



