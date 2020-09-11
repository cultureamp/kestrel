package com.cultureamp.eventsourcing.example

import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.util.*

class SurveyNamesCommandProjector(private val database: Database) {
    init {
        transaction(database) {
            SchemaUtils.create(SurveyNames)
        }
    }

    fun project(event: SurveyEvent, aggregateId: UUID): Any = transaction(database) {
        when (event) {
            is Created -> event.name.forEach { locale, name ->
                SurveyNames.insert {
                    it[surveyId] = aggregateId
                    it[accountId] = event.accountId
                    it[SurveyNames.locale] = locale
                    it[SurveyNames.name] = name
                }
            }
            is Renamed ->
                SurveyNames.update({ SurveyNames.surveyId eq aggregateId }) {
                    it[locale] = event.locale
                    it[name] = event.name
                }
            is Deleted ->
                SurveyNames.deleteWhere { SurveyNames.surveyId eq aggregateId }
            is Restored -> Unit
        }
    }
}

object SurveyNames : Table() {
    val surveyId = uuid("survey_id")
    val accountId = uuid("account_id")
    val locale = enumerationByName("locale",  10, Locale::class)
    val name = text("name").index()
}