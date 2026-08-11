package com.cultureamp.eventsourcing

import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.max
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.upsert
import kotlin.reflect.KClass

val defaultEventsSequenceStatsTableName = "events_sequence_stats"

interface EventsSequenceStats {
    fun lastSequence(eventClasses: List<KClass<out DomainEvent>> = emptyList()): Long
    fun save(eventClass: KClass<out DomainEvent>, sequence: Long)
}

class RelationalDatabaseEventsSequenceStats(
    private val db: Database,
    private val eventTypeResolver: EventTypeResolver = defaultEventTypeResolver,
    tableName: String = defaultEventsSequenceStatsTableName,
) : EventsSequenceStats {
    val table = EventsSequenceStatsTable(tableName)

    fun createSchemaIfNotExists() {
        transaction(db) {
            SchemaUtils.create(table)
        }
    }

    override fun save(eventClass: KClass<out DomainEvent>, sequence: Long) = transaction(db) {
        table.upsert {
            it[table.eventType] = eventTypeResolver.serialize(eventClass.java).eventType
            it[table.sequence] = sequence
        }
        Unit
    }

    override fun lastSequence(eventClasses: List<KClass<out DomainEvent>>): Long = transaction(db) {
        val maxSequence = table.sequence.max()
        table
            .select(maxSequence)
            .where {
                if (eventClasses.isNotEmpty()) {
                    val eventTypeDefinitions = eventClasses.map { eventTypeResolver.serialize(it.java) }
                    val eventTypes = eventTypeDefinitions.eventTypes()
                    if (eventTypes.isNotEmpty()) table.eventType.inList(eventTypes) else Op.TRUE
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
