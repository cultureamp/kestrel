package com.cultureamp.eventsourcing

import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.StringColumnType
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.statements.api.PreparedStatementApi
import org.postgresql.util.PGobject

fun Table.jsonb(name: String): Column<String> =
    registerColumn(name, Jsonb())

private class Jsonb : StringColumnType() {
    override fun sqlType() = "jsonb"

    override fun setParameter(stmt: PreparedStatementApi, index: Int, value: Any?) {
        if (value is String) {
            val pgObject = PGobject()
            pgObject.type = "jsonb"
            pgObject.value = value
            stmt[index] = pgObject
        } else {
            stmt[index] = value!!
        }
    }

    override fun valueFromDB(value: Any): Any {
        return if (value is PGobject) {
            value.value!!
        } else {
            value.toString()
        }
    }
}
