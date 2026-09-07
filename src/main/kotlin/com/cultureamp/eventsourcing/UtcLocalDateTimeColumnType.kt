package com.cultureamp.eventsourcing

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ColumnType
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.statements.api.RowApi
import org.jetbrains.exposed.v1.core.vendors.currentDialect
import java.time.LocalDateTime

/**
 * A `timestamp without time zone` column carried as a [LocalDateTime], with no time zone involved at any point.
 *
 * Exposed's own `datetime` type moves values through `java.sql.Timestamp`, which is an instant, so every read and
 * every bound parameter is converted from a wall-clock time to an instant and back through the JVM's default zone. The
 * two conversions cancel out except when the wall-clock value does not exist in that zone: in the hour a DST
 * transition skips, a column value of `02:30` is read as `03:30`, and a position of `02:30` reaches the database as
 * `03:30`. For a position that is an hour of rows lost, so Kestrel reads and binds every position through this type,
 * whatever type the consumer mapped the column with. It uses the JDBC 4.2 `LocalDateTime` mapping, which pgjdbc and H2
 * both implement without consulting a zone.
 *
 * Map a column with [utcDatetime]. That is worth doing for the polled column itself, so that `rowToEntity` reads it the
 * same way, and it is required of any hand-written [EntitySource], which binds `after` and `safeBefore` for itself.
 */
class UtcLocalDateTimeColumnType : ColumnType<LocalDateTime>() {
    override fun sqlType(): String = currentDialect.dataTypeProvider.dateTimeType()

    override fun readObject(rs: RowApi, index: Int): Any? = rs.getObject(index, LocalDateTime::class.java)

    override fun valueFromDB(value: Any): LocalDateTime = when (value) {
        is LocalDateTime -> value
        else -> throw IllegalStateException(
            "Expected the driver to supply a java.time.LocalDateTime but got ${value::class.qualifiedName}. A " +
                "java.sql.Timestamp here would have been built in the JVM's default zone, which is the conversion this " +
                "type exists to avoid.",
        )
    }

    override fun notNullValueToDB(value: LocalDateTime): Any = value

    override fun nonNullValueToString(value: LocalDateTime): String = "'${value.toString().replace('T', ' ')}'"
}

/**
 * Registers a `timestamp without time zone` column read and written as a naive [LocalDateTime] with no zone
 * conversion, unlike Exposed's `datetime`. See [UtcLocalDateTimeColumnType] for why that matters for a position.
 */
fun Table.utcDatetime(name: String): Column<LocalDateTime> = registerColumn(name, UtcLocalDateTimeColumnType())
