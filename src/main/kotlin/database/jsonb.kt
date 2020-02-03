package database


import com.fasterxml.jackson.databind.ObjectMapper
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.ColumnType
import org.jetbrains.exposed.sql.StringColumnType
import org.jetbrains.exposed.sql.Table
import org.postgresql.util.PGobject
import java.sql.PreparedStatement

/**
 * Modified from: https://gist.github.com/quangIO/a623b5caa53c703e252d858f7a806919
 */

fun Table.jsonb(name: String): Column<String> =
    registerColumn(name, Jsonb())


private class Jsonb : StringColumnType() {
    override fun sqlType() = "jsonb"

    override fun setParameter(stmt: PreparedStatement, index: Int, value: Any?) {
        val obj = PGobject()
        obj.type = "jsonb"
        obj.value = value as String
        stmt.setObject(index, obj)
    }

    override fun valueFromDB(value: Any): Any {
        value as PGobject
        return value.value
    }
}