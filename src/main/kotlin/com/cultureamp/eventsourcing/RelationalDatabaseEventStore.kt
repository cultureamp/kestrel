package com.cultureamp.eventsourcing

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS
import com.fasterxml.jackson.datatype.joda.JodaModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.vendors.H2Dialect
import org.jetbrains.exposed.sql.vendors.PostgreSQLDialect
import org.joda.time.DateTime
import java.lang.UnsupportedOperationException
import java.util.*
import kotlin.reflect.full.companionObjectInstance

interface MetadataClassProvider {
    val metadataClass: Class<out EventMetadata>
}

class RelationalDatabaseEventStore internal constructor(
    private val db: Database,
    private val events: Events,
    synchronousProjectors: List<EventListener>,
    private val defaultMetadataClass: Class<out EventMetadata>
) : EventStore {
    companion object {
        fun create(synchronousProjectors: List<EventListener>, db: Database, defaultMetadataClass: Class<out EventMetadata> = EventMetadata::class.java): RelationalDatabaseEventStore =
            when (db.dialect) {
                is H2Dialect -> H2DatabaseEventStore.create(synchronousProjectors, db, defaultMetadataClass)
                is PostgreSQLDialect -> PostgresDatabaseEventStore.create(synchronousProjectors, db, defaultMetadataClass)
                else -> throw UnsupportedOperationException("${db.dialect} not currently supported")
            }

        fun create(db: Database, defaultMetadataClass: Class<out EventMetadata> = EventMetadata::class.java) = create(emptyList(), db, defaultMetadataClass)
    }

    override val listeners: MutableList<EventListener> = synchronousProjectors.toMutableList()

    override fun setUp() {
        transaction(db) {
            // TODO don't do this if pointing directly to Murmur DB or potentially introduce separate migrations
            SchemaUtils.create(events)
        }
    }

    override fun sink(newEvents: List<Event>, aggregateId: UUID, aggregateType: String): Either<CommandError, Unit> {
        return try {
            return transaction(db) {
                newEvents.forEach { event ->
                    val body = OBJECT_MAPPER.writeValueAsString(event.domainEvent)
                    val eventType = event.domainEvent.javaClass
                    // prove that json body can be deserialized, which catches invalid fields types, e.g. interfaces
                    OBJECT_MAPPER.readValue<DomainEvent>(body, eventType)
                    val metadata = OBJECT_MAPPER.writeValueAsString(event.metadata)
                    OBJECT_MAPPER.readValue(metadata, metadataClassFor(eventType))
                    events.insert { row ->
                        row[events.aggregateSequence] = event.aggregateSequence
                        row[events.eventId] = event.id
                        row[events.aggregateId] = aggregateId
                        row[events.aggregateType] = aggregateType
                        row[events.eventType] = eventType.canonicalName
                        row[events.createdAt] = DateTime.now()
                        row[events.body] = body
                        row[events.metadata] = metadata
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

    private fun rowToSequencedEvent(row: ResultRow): SequencedEvent = row.let {
        val eventType = row[events.eventType].asClass<DomainEvent>()!!
        val domainEvent = OBJECT_MAPPER.readValue(row[events.body], eventType)
        val metadata = OBJECT_MAPPER.readValue(row[events.metadata], metadataClassFor(eventType))

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

    override fun replay(aggregateType: String, project: (Event) -> Unit) {
        return transaction(db) {
            events
                .select {
                    events.aggregateType eq aggregateType
                }
                .orderBy(events.sequence)
                .mapLazy(::rowToSequencedEvent)
                .mapLazy { it.event }
                .forEach(project)
        }
    }

    override fun getAfter(sequence: Long, batchSize: Int): List<SequencedEvent> {
        return transaction(db) {
            events
                .select {
                    events.sequence greater sequence
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

    private fun metadataClassFor(eventType: Class<out DomainEvent>): Class<out EventMetadata> {
        return try {
            (eventType.kotlin.companionObjectInstance as MetadataClassProvider).metadataClass
        } catch (_: ClassCastException) {
            defaultMetadataClass
        }
    }
}

object PostgresDatabaseEventStore {
    internal fun create(synchronousProjectors: List<EventListener>, db: Database, defaultMetadataClass: Class<out EventMetadata>): RelationalDatabaseEventStore {
        return RelationalDatabaseEventStore(db, Events(Table::jsonb), synchronousProjectors, defaultMetadataClass)
    }
}

object H2DatabaseEventStore {
    internal fun create(synchronousProjectors: List<EventListener>, db: Database, defaultMetadataClass: Class<out EventMetadata>): RelationalDatabaseEventStore {
        return RelationalDatabaseEventStore(db, eventsTable(), synchronousProjectors, defaultMetadataClass)
    }

    fun eventsTable() = Events { name -> this.text(name) }
}

private fun <T> String.asClass(): Class<out T>? {
    return Class.forName(this) as Class<out T>?
}

private val OBJECT_MAPPER  = ObjectMapper().registerKotlinModule().registerModule(JodaModule()).configure(
    WRITE_DATES_AS_TIMESTAMPS, false)

class Events(jsonb: Table.(String) -> Column<String>) : Table() {
    val sequence = long("sequence").autoIncrement().index()
    val eventId = uuid("id")
    val aggregateSequence = long("aggregate_sequence").primaryKey(1)
    val aggregateId = uuid("aggregate_id").primaryKey(0)
    val aggregateType = varchar("aggregate_type", 128)
    val eventType = varchar("event_type", 128)
    val createdAt = datetime("created_at")
    val body = jsonb("json_body")
    val metadata = jsonb("metadata")
}

object ConcurrencyError : RetriableError
