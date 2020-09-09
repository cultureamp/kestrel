package com.cultureamp.eventsourcing.fixtures

import java.util.*

interface SurveyNamesQuery  {
    fun nameExistsFor(accountId: UUID, name: String, locale: Locale): Boolean
}

object SurveyNameAlwaysAvailable : SurveyNamesQuery {
    override fun nameExistsFor(accountId: UUID, name: String, locale: Locale) = false
}