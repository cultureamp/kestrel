package com.cultureamp.eventsourcing


import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.StringColumnType
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.statements.api.PreparedStatementApi

/**
 * Modified from: https://gist.github.com/quangIO/a623b5caa53c703e252d858f7a806919
 */

fun Table.jsonb(name: String): Column<String> =
    registerColumn(name, Jsonb())


private class Jsonb : StringColumnType() {
    override fun sqlType() = "jsonb"

    override fun setParameter(stmt: PreparedStatementApi, index: Int, value: Any?) {
        stmt[index] = value as String
    }

    override fun valueFromDB(value: Any): Any {
        return value
    }
}