package com.cultureamp.eventsourcing.example

import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.*

interface SurveyNamesQuery  {
    fun nameExistsFor(accountId: UUID, name: String, locale: Locale): Boolean
}

object SurveyNameAlwaysAvailable : SurveyNamesQuery {
    override fun nameExistsFor(accountId: UUID, name: String, locale: Locale) = false
}

class RelationalDatabaseSurveyNamesQuery internal constructor(private val database: Database) : SurveyNamesQuery {
    override fun nameExistsFor(accountId: UUID, name: String, locale: Locale) = transaction(database) {
        SurveyNames.select { (SurveyNames.name eq name) and (SurveyNames.locale eq locale) }.any()
    }
}