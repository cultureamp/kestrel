package com.cultureamp.eventsourcing

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.StringColumnType
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.statements.api.PreparedStatementApi
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
            stmt.set(index, pgObject, this)
        } else {
            stmt.set(index, value!!, this)
        }
    }

    override fun valueFromDB(value: Any): String {
        return if (value is PGobject) {
            value.value!!
        } else {
            value.toString()
        }
    }
}
