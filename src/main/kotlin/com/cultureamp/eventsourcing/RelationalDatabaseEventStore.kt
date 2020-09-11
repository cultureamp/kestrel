package com.cultureamp.eventsourcing

import arrow.data.extensions.list.foldable.nonEmpty
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
import kotlin.reflect.KClass

val defaultObjectMapper = ObjectMapper()
    .registerKotlinModule()
    .registerModule(JodaModule())
    .configure(WRITE_DATES_AS_TIMESTAMPS, false)
    .setPropertyNamingStrategy(PropertyNamingStrategy.SNAKE_CASE)

class RelationalDatabaseEventStore @PublishedApi internal constructor(
    private val db: Database,
    private val events: Events,
    private val synchronousProjectors: List<EventListener>,
    private val metadataClass: Class<out EventMetadata>,
    private val objectMapper: ObjectMapper
) : EventStore {

    companion object {
        inline fun <reified T: EventMetadata> create(
            synchronousProjectors: List<EventListener>,
            db: Database,
            objectMapper: ObjectMapper = defaultObjectMapper
        ): RelationalDatabaseEventStore =
            when (db.dialect) {
                is H2Dialect -> H2DatabaseEventStore.create<T>(synchronousProjectors, db, objectMapper)
                is PostgreSQLDialect -> PostgresDatabaseEventStore.create<T>(synchronousProjectors, db, objectMapper)
                else -> throw UnsupportedOperationException("${db.dialect} not currently supported")
            }

        inline fun <reified T : EventMetadata> create(
            db: Database,
            objectMapper: ObjectMapper = defaultObjectMapper
        ) =
            create<T>(emptyList(), db, objectMapper)
    }

    fun createSchemaIfNotExists() {
        transaction(db) {
            SchemaUtils.create(events)
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
                    events.insert { row ->
                        row[events.aggregateSequence] = event.aggregateSequence
                        row[events.eventId] = event.id
                        row[events.aggregateId] = aggregateId
                        row[events.aggregateType] = aggregateType
                        row[events.eventType] = eventType.canonicalName
                        row[events.createdAt] = event.createdAt
                        row[events.body] = body
                        row[events.metadata] = metadata
                    }
                }

                updateSynchronousProjections(newEvents)
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
        val eventType = row[events.eventType].asClass<DomainEvent>()!!
        val domainEvent = objectMapper.readValue(row[events.body], eventType)
        val metadata = objectMapper.readValue(row[events.metadata], metadataClass)

        SequencedEvent(
            Event(
                id = row[events.eventId],
                aggregateId = row[events.aggregateId],
                aggregateSequence = row[events.aggregateSequence],
                createdAt = row[events.createdAt],
                metadata = metadata,
                domainEvent = domainEvent
            ), row[events.sequence]
        )
    }

    override fun getAfter(sequence: Long, eventClasses: List<KClass<out DomainEvent>>, batchSize: Int): List<SequencedEvent> {
        return transaction(db) {
            events
                .select {
                    val eventTypeMatches = if (eventClasses.nonEmpty()) {
                        events.eventType.inList(eventClasses.map { it.java.canonicalName })
                    } else {
                        Op.TRUE
                    }
                    events.sequence greater sequence and eventTypeMatches
                }
                .orderBy(events.sequence)
                .limit(batchSize)
                .map(::rowToSequencedEvent)
        }
    }

    override fun eventsFor(aggregateId: UUID): List<Event> {
        return transaction(db) {
            events
                .select { events.aggregateId eq aggregateId }
                .orderBy(events.sequence)
                .map(::rowToSequencedEvent)
                .map { it.event }
        }
    }

    override fun lastSequence(eventClasses: List<KClass<out DomainEvent>>): Long = transaction(db) {
        val maxSequence = events.sequence.max()
        events
            .slice(maxSequence)
            .select {
                if (eventClasses.nonEmpty()) {
                    events.eventType.inList(eventClasses.map { it.java.canonicalName })
                } else {
                    Op.TRUE
                }
            }
            .map { it[maxSequence] }
            .first() ?: 0
    }

    private fun updateSynchronousProjections(newEvents: List<Event>) {
        newEvents.forEach { event -> synchronousProjectors.forEach { it.handle(event) } }
    }
}

open class EventDataException(e: Exception) : Throwable(e)
class EventBodySerializationException(e: Exception) : EventDataException(e)
class EventMetadataSerializationException(e: Exception) : EventDataException(e)

object PostgresDatabaseEventStore {
    @PublishedApi internal inline fun <reified T: EventMetadata> create(
        synchronousProjectors: List<EventListener>,
        db: Database,
        objectMapper: ObjectMapper
    ): RelationalDatabaseEventStore {
        return RelationalDatabaseEventStore(db, Events(Table::jsonb), synchronousProjectors, T::class.java, objectMapper)
    }
}

object H2DatabaseEventStore {
    // need a `@PublishedApi` here to make it callable from `RelationalDatabaseEventStore.create()`
    @PublishedApi internal inline fun <reified T: EventMetadata> create(
        synchronousProjectors: List<EventListener>,
        db: Database,
        objectMapper: ObjectMapper
    ): RelationalDatabaseEventStore {
        return RelationalDatabaseEventStore(db, eventsTable(), synchronousProjectors, T::class.java, objectMapper)
    }

    @PublishedApi internal fun eventsTable() = Events { name -> this.text(name) }
}

private fun <T> String.asClass(): Class<out T>? {
    @Suppress("UNCHECKED_CAST")
    return Class.forName(this) as Class<out T>?
}

class Events(jsonb: Table.(String) -> Column<String>) : Table() {
    val sequence = long("sequence").autoIncrement().primaryKey()
    val eventId = uuid("id")
    val aggregateSequence = long("aggregate_sequence")
    val aggregateId = uuid("aggregate_id")
    val aggregateType = varchar("aggregate_type", 128)
    val eventType = varchar("event_type", 256)
    val createdAt = datetime("created_at")
    val body = jsonb("json_body")
    val metadata = jsonb("metadata")

    init {
        uniqueIndex(eventId)
        uniqueIndex(aggregateId, aggregateSequence)
        nonUniqueIndex(eventType, aggregateType)
    }
}

private fun Table.nonUniqueIndex(vararg columns: Column<*>) = index(false, *columns)

object ConcurrencyError : RetriableError
