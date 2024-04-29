package com.cultureamp.eventsourcing

import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.StdOutSqlLogger
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.addLogger
import org.jetbrains.exposed.sql.max
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.upsert
import kotlin.reflect.KClass

val defaultEventsSequenceStatsTableName = "events_sequence_stats"

interface EventsSequenceStats {
    fun lastSequence(eventClasses: List<KClass<out DomainEvent>> = emptyList()): Long
    fun update(eventClass: KClass<out DomainEvent>, sequence: Long)
}

class RelationalDatabaseEventsSequenceStats(
    private val db: Database,
    private val eventTypeResolver: EventTypeResolver = defaultEventTypeResolver,
    tableName: String = defaultEventsSequenceStatsTableName,
) : EventsSequenceStats {
    val table = EventsSequenceStatsTable(tableName)

    fun createSchemaIfNotExists() {
        transaction(db) {
            println("creating table ${table.tableName}")
            addLogger(StdOutSqlLogger)
            SchemaUtils.create(table)
        }
    }

    override fun update(eventClass: KClass<out DomainEvent>, sequence: Long) = transaction(db) {
        table.upsert {
            it[table.eventType] = eventTypeResolver.serialize(eventClass.java)
            it[table.sequence] = sequence
        }
        Unit
    }

    override fun lastSequence(eventClasses: List<KClass<out DomainEvent>>): Long = transaction(db) {
        val maxSequence = table.sequence.max()
        table
            .slice(maxSequence)
            .select {
                if (eventClasses.isNotEmpty()) {
                    table.eventType.inList(eventClasses.map { eventTypeResolver.serialize(it.java) })
                } else {
                    Op.TRUE
                }
            }
            .map { it[maxSequence] }
            .first() ?: 0
    }
}

class EventsSequenceStatsTable(tableName: String = defaultEventsSequenceStatsTableName) : Table(tableName) {
    val eventType = varchar("event_type", 256)
    override val primaryKey = PrimaryKey(eventType)
    val sequence = long("sequence")
}
