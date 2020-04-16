package com.cultureamp.eventsourcing

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.PropertyNamingStrategy
import com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS
import com.fasterxml.jackson.datatype.joda.JodaModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.vendors.H2Dialect
import org.jetbrains.exposed.sql.vendors.PostgreSQLDialect
import java.lang.UnsupportedOperationException
import java.util.*

val defaultObjectMapper = ObjectMapper()
    .registerKotlinModule()
    .registerModule(JodaModule())
    .configure(WRITE_DATES_AS_TIMESTAMPS, false)
    .setPropertyNamingStrategy(PropertyNamingStrategy.SNAKE_CASE)

class RelationalDatabaseEventStore @PublishedApi internal constructor(
    private val db: Database,
    val table: Events,
    synchronousProjectors: List<EventListener>,
    private val metadataClass: Class<out EventMetadata>,
    private val objectMapper: ObjectMapper
) : EventStore {

    companion object {
        inline fun <reified T: EventMetadata> create(
            synchronousProjectors: List<EventListener>,
            db: Database,
            objectMapper: ObjectMapper = defaultObjectMapper,
            tableName: String = "events"
        ): RelationalDatabaseEventStore =
            when (db.dialect) {
                is H2Dialect -> H2DatabaseEventStore.create<T>(synchronousProjectors, db, objectMapper, tableName)
                is PostgreSQLDialect -> PostgresDatabaseEventStore.create<T>(synchronousProjectors, db, objectMapper, tableName)
                else -> throw UnsupportedOperationException("${db.dialect} not currently supported")
            }

        inline fun <reified T : EventMetadata> create(
            db: Database,
            objectMapper: ObjectMapper = defaultObjectMapper,
            tableName: String = "events"
        ) =
            create<T>(emptyList(), db, objectMapper, tableName)
    }

    override val listeners: MutableList<EventListener> = synchronousProjectors.toMutableList()

    fun createSchemaIfNotExists() {
        transaction(db) {
            SchemaUtils.create(table)
        }
    }


    override fun sink(newEvents: List<Event>, aggregateId: UUID, aggregateType: String): Either<CommandError, Unit> {
        return try {
            return transaction(db) {
                newEvents.forEach { event ->
                    val body = objectMapper.writeValueAsString(event.domainEvent)
                    val eventType = event.domainEvent.javaClass
                    val metadata = objectMapper.writeValueAsString(event.metadata)
                    validateSerialization(eventType, body, metadata)
                    table.insert { row ->
                        row[table.aggregateSequence] = event.aggregateSequence
                        row[table.eventId] = event.id
                        row[table.aggregateId] = aggregateId
                        row[table.aggregateType] = aggregateType
                        row[table.eventType] = eventType.canonicalName
                        row[table.createdAt] = event.createdAt
                        row[table.body] = body
                        row[table.metadata] = metadata
                    }
                }

                notifyListeners(newEvents, aggregateId)
                Right(Unit)
            }
        } catch (e: ExposedSQLException) {
            if (e.message.orEmpty().contains("violates unique constraint")) {
                Left(ConcurrencyError)
            } else {
                throw e
            }
        }
    }

    private fun validateSerialization(eventType: Class<DomainEvent>, body: String, metadata: String) {
        // prove that json body can be deserialized, which catches invalid fields types, e.g. interfaces
        try {
            objectMapper.readValue<DomainEvent>(body, eventType)
        } catch (e: JsonProcessingException) {
            throw EventBodySerializationException(e)
        }

        try {
            objectMapper.readValue(metadata, metadataClass)
        } catch (e: JsonProcessingException) {
            throw EventMetadataSerializationException(e)
        }
    }

    private fun rowToSequencedEvent(row: ResultRow): SequencedEvent = row.let {
        val eventType = row[table.eventType].asClass<DomainEvent>()!!
        val domainEvent = objectMapper.readValue(row[table.body], eventType)
        val metadata = objectMapper.readValue(row[table.metadata], metadataClass)

        SequencedEvent(
            Event(
                id = row[table.eventId],
                aggregateId = row[table.aggregateId],
                aggregateSequence = row[table.aggregateSequence],
                createdAt = row[table.createdAt],
                metadata = metadata,
                domainEvent = domainEvent
            ), row[table.sequence]
        )
    }

    override fun replay(aggregateType: String, project: (Event) -> Unit) {
        return transaction(db) {
            table
                .select {
                    table.aggregateType eq aggregateType
                }
                .orderBy(table.sequence)
                .mapLazy(::rowToSequencedEvent)
                .mapLazy { it.event }
                .forEach(project)
        }
    }

    override fun getAfter(sequence: Long, batchSize: Int): List<SequencedEvent> {
        return transaction(db) {
            table
                .select {
                    table.sequence greater sequence
                }
                .orderBy(table.sequence)
                .limit(batchSize)
                .map(::rowToSequencedEvent)
        }
    }

    override fun eventsFor(aggregateId: UUID): List<Event> {
        return transaction(db) {
            table
                .select { table.aggregateId eq aggregateId }
                .orderBy(table.sequence)
                .map(::rowToSequencedEvent)
                .map { it.event }
        }
    }
}

open class EventDataException(e: Exception) : Throwable(e)
class EventBodySerializationException(e: Exception) : EventDataException(e)
class EventMetadataSerializationException(e: Exception) : EventDataException(e)

object PostgresDatabaseEventStore {
    @PublishedApi internal inline fun <reified T: EventMetadata> create(
        synchronousProjectors: List<EventListener>,
        db: Database,
        objectMapper: ObjectMapper,
        tableName: String
    ): RelationalDatabaseEventStore {
        return RelationalDatabaseEventStore(db, Events(Table::jsonb, tableName), synchronousProjectors, T::class.java, objectMapper)
    }
}

object H2DatabaseEventStore {
    // need a `@PublishedApi` here to make it callable from `RelationalDatabaseEventStore.create()`
    @PublishedApi internal inline fun <reified T: EventMetadata> create(
        synchronousProjectors: List<EventListener>,
        db: Database,
        objectMapper: ObjectMapper,
        tableName: String
    ): RelationalDatabaseEventStore {
        return RelationalDatabaseEventStore(db, eventsTable(tableName), synchronousProjectors, T::class.java, objectMapper)
    }

    @PublishedApi internal fun eventsTable(name: String) = Events({ colName -> this.text(colName) }, name)
}

private fun <T> String.asClass(): Class<out T>? {
    @Suppress("UNCHECKED_CAST")
    return Class.forName(this) as Class<out T>?
}

class Events(jsonb: Table.(String) -> Column<String>, name: String = "events") : Table(name) {
    val sequence = long("sequence").autoIncrement().index()
    val eventId = uuid("id")
    val aggregateSequence = long("aggregate_sequence").primaryKey(1)
    val aggregateId = uuid("aggregate_id").primaryKey(0)
    val aggregateType = varchar("aggregate_type", 128)
    val eventType = varchar("event_type", 256)
    val createdAt = datetime("created_at")
    val body = jsonb("json_body")
    val metadata = jsonb("metadata")
}

object ConcurrencyError : RetriableError
