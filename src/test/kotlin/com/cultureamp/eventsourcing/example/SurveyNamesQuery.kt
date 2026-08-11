package com.cultureamp.eventsourcing.example

import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.*

interface SurveyNamesQuery  {
    fun nameExistsFor(accountId: UUID, name: String, locale: Locale): Boolean
}

object SurveyNameAlwaysAvailable : SurveyNamesQuery {
    override fun nameExistsFor(accountId: UUID, name: String, locale: Locale) = false
}

class RelationalDatabaseSurveyNamesQuery internal constructor(private val database: Database) : SurveyNamesQuery {
    override fun nameExistsFor(accountId: UUID, name: String, locale: Locale) = transaction(database) {
        SurveyNames.selectAll().where { (SurveyNames.name eq name) and (SurveyNames.locale eq locale) }.any()
    }
}