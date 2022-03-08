package com.cultureamp.eventsourcing

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS
import com.fasterxml.jackson.datatype.joda.JodaModule
import com.fasterxml.jackson.module.kotlin.SingletonSupport
import com.fasterxml.jackson.module.kotlin.kotlinModule
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.jodatime.datetime
import org.jetbrains.exposed.sql.max
import org.jetbrains.exposed.sql.replace
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.vendors.H2Dialect
import org.jetbrains.exposed.sql.vendors.PostgreSQLDialect
import org.postgresql.util.PSQLException
import java.util.UUID
import kotlin.reflect.KClass

val defaultObjectMapper = ObjectMapper()
    .registerModule(kotlinModule { singletonSupport(SingletonSupport.CANONICALIZE) })
    .registerModule(JodaModule())
    .configure(WRITE_DATES_AS_TIMESTAMPS, false)
    .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)

val defaultEventsTableName = "events"
val defaultEventsSequenceStatsTableName = "events_sequence_stats"
val defaultEventTypeResolver = CanonicalNameEventTypeResolver

class RelationalDatabaseEventStore<M : EventMetadata> @PublishedApi internal constructor(
    private val db: Database,
    private val events: Events,
    private val eventsSequenceStats: EventsSequenceStats,
    private val synchronousEventProcessors: List<EventProcessor<M>>,
    private val metadataClass: Class<M>,
    private val objectMapper: ObjectMapper,
    private val eventTypeResolver: EventTypeResolver,
    private val blockingLockUntilTransactionEnd: Transaction.() -> CommandError? = { null }
) : EventStore<M> {

    companion object {
        inline fun <reified M : EventMetadata> create(
            synchronousEventProcessors: List<EventProcessor<M>>,
            db: Database,
            objectMapper: ObjectMapper = defaultObjectMapper,
            eventsTableName: String = defaultEventsTableName,
            eventsSequenceStateTableName: String = defaultEventsSequenceStatsTableName,
            eventTypeResolver: EventTypeResolver = defaultEventTypeResolver
        ): RelationalDatabaseEventStore<M> =
            when (db.dialect) {
                is H2Dialect -> H2DatabaseEventStore.create(synchronousEventProcessors, db, objectMapper, eventsTableName, eventsSequenceStateTableName, eventTypeResolver)
                is PostgreSQLDialect -> PostgresDatabaseEventStore.create(synchronousEventProcessors, db, objectMapper, eventsTableName, eventsSequenceStateTableName, eventTypeResolver)
                else -> throw UnsupportedOperationException("${db.dialect} not currently supported")
            }

        inline fun <reified M : EventMetadata> create(
            db: Database,
            objectMapper: ObjectMapper = defaultObjectMapper,
            eventsTableName: String = defaultEventsTableName,
            eventsSequenceStateTableName: String = defaultEventsSequenceStatsTableName,
            eventTypeResolver: EventTypeResolver = defaultEventTypeResolver
        ) =
            create<M>(emptyList(), db, objectMapper, eventsTableName, eventsSequenceStateTableName, eventTypeResolver)
    }

    fun createSchemaIfNotExists() {
        transaction(db) {
            SchemaUtils.create(events, eventsSequenceStats)
        }
    }

    override fun sink(newEvents: List<Event<M>>, aggregateId: UUID): Either<CommandError, Unit> {
        return try {
            return transaction(db) {
                blockingLockUntilTransactionEnd()?.let { Left(it) } ?: run {
                    newEvents.forEach { event ->
                        val body = objectMapper.writeValueAsString(event.domainEvent)
                        val domainEventClass = event.domainEvent.javaClass
                        val metadata = objectMapper.writeValueAsString(event.metadata)
                        validateSerialization(domainEventClass, body, metadata)
                        val eventType = eventTypeResolver.serialize(domainEventClass)
                        val insertResult = events.insert { row ->
                            row[events.aggregateSequence] = event.aggregateSequence
                            row[events.eventId] = event.id
                            row[events.aggregateId] = aggregateId
                            row[events.aggregateType] = event.aggregateType
                            row[events.eventType] = eventType
                            row[events.createdAt] = event.createdAt
                            row[events.body] = body
                            row[events.metadata] = metadata
                        }
                        eventsSequenceStats.replace {
                            it[eventsSequenceStats.eventType] = eventType
                            it[eventsSequenceStats.sequence] = insertResult[events.sequence]
                        }
                        synchronousEventProcessors.forEach { it.process(event) }
                    }
                    Right(Unit)
                }
            }
        } catch (e: ExposedSQLException) {
            if (e.message.orEmpty().contains("violates unique constraint") || e.message.orEmpty().contains("Unique index or primary key violation")) {
                Left(ConcurrencyError)
            } else {
                throw e
            }
        }
    }

    private fun validateSerialization(domainEventClass: Class<DomainEvent>, body: String, metadata: String) {
        // prove that json body can be deserialized, which catches invalid fields types, e.g. interfaces
        try {
            objectMapper.readValue(body, domainEventClass)
        } catch (e: JsonProcessingException) {
            throw EventBodySerializationException(e)
        }

        try {
            objectMapper.readValue(metadata, metadataClass)
        } catch (e: JsonProcessingException) {
            throw EventMetadataSerializationException(e)
        }
    }

    private fun rowToSequencedEvent(row: ResultRow): SequencedEvent<M> = row.let {
        val eventType = eventTypeResolver.deserialize(row[events.aggregateType], row[events.eventType])
        val domainEvent = objectMapper.readValue(row[events.body], eventType)
        val metadata = objectMapper.readValue(row[events.metadata], metadataClass)

        SequencedEvent(
            Event(
                id = row[events.eventId],
                aggregateId = row[events.aggregateId],
                aggregateSequence = row[events.aggregateSequence],
                aggregateType = row[events.aggregateType],
                createdAt = row[events.createdAt],
                metadata = metadata,
                domainEvent = domainEvent
            ),
            row[events.sequence]
        )
    }

    override fun getAfter(sequence: Long, eventClasses: List<KClass<out DomainEvent>>, batchSize: Int): List<SequencedEvent<M>> {
        return transaction(db) {
            events
                .select {
                    val eventTypeMatches = if (eventClasses.isNotEmpty()) {
                        events.eventType.inList(eventClasses.map { eventTypeResolver.serialize(it.java) })
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

    override fun eventsFor(aggregateId: UUID): List<Event<M>> {
        return transaction(db) {
            events
                .select { events.aggregateId eq aggregateId }
                .orderBy(events.sequence)
                .map(::rowToSequencedEvent)
                .map { it.event }
        }
    }

    override fun lastSequence(eventClasses: List<KClass<out DomainEvent>>): Long = transaction(db) {
        val maxSequence = eventsSequenceStats.sequence.max()
        eventsSequenceStats
            .slice(maxSequence)
            .select {
                if (eventClasses.isNotEmpty()) {
                    eventsSequenceStats.eventType.inList(eventClasses.map { eventTypeResolver.serialize(it.java) })
                } else {
                    Op.TRUE
                }
            }
            .map { it[maxSequence] }
            .first() ?: 0
    }
}

open class EventDataException(e: Exception) : Throwable(e)
class EventBodySerializationException(e: Exception) : EventDataException(e)
class EventMetadataSerializationException(e: Exception) : EventDataException(e)

object PostgresDatabaseEventStore {
    @PublishedApi
    internal inline fun <reified M : EventMetadata> create(
        synchronousEventProcessors: List<EventProcessor<M>>,
        db: Database,
        objectMapper: ObjectMapper,
        tableName: String,
        eventsSequenceStateTableName: String,
        eventTypeResolver: EventTypeResolver
    ): RelationalDatabaseEventStore<M> {
        return RelationalDatabaseEventStore(db, Events(tableName, Table::jsonb), EventsSequenceStats(eventsSequenceStateTableName), synchronousEventProcessors, M::class.java, objectMapper, eventTypeResolver, Transaction::pgAdvisoryXactLock)
    }
}

object H2DatabaseEventStore {
    // need a `@PublishedApi` here to make it callable from `RelationalDatabaseEventStore.create()`
    @PublishedApi
    internal inline fun <reified M : EventMetadata> create(
        synchronousEventProcessors: List<EventProcessor<M>>,
        db: Database,
        objectMapper: ObjectMapper,
        tableName: String,
        eventsSequenceStateTableName: String,
        eventTypeResolver: EventTypeResolver
    ): RelationalDatabaseEventStore<M> {
        return RelationalDatabaseEventStore(db, eventsTable(tableName), EventsSequenceStats(eventsSequenceStateTableName), synchronousEventProcessors, M::class.java, objectMapper, eventTypeResolver)
    }

    @PublishedApi
    internal fun eventsTable(tableName: String = defaultEventsTableName) = Events(tableName) { name -> this.text(name) }
}

internal fun <T> String.asClass(): Class<out T>? {
    @Suppress("UNCHECKED_CAST")
    return Class.forName(this) as Class<out T>?
}

class Events(tableName: String = defaultEventsTableName, jsonb: Table.(String) -> Column<String> = Table::jsonb) :
    Table(tableName) {
    val sequence = long("sequence").autoIncrement()
    val eventId = uuid("id")
    val aggregateSequence = long("aggregate_sequence")
    val aggregateId = uuid("aggregate_id")
    val aggregateType = varchar("aggregate_type", 128)
    val eventType = varchar("event_type", 256)
    val createdAt = datetime("created_at")
    val body = jsonb("json_body")
    val metadata = jsonb("metadata")
    override val primaryKey: PrimaryKey = PrimaryKey(sequence)

    init {
        uniqueIndex(eventId)
        uniqueIndex(aggregateId, aggregateSequence)
        nonUniqueIndex(eventType, aggregateType)
    }
}

class EventsSequenceStats(tableName: String = defaultEventsSequenceStatsTableName) : Table(tableName) {
    val eventType = varchar("event_type", 256)
    override val primaryKey = PrimaryKey(eventType)
    val sequence = long("sequence")
}

private fun Table.nonUniqueIndex(vararg columns: Column<*>) = index(false, *columns)

object ConcurrencyError : RetriableError
object LockingError : CommandError

fun Transaction.pgAdvisoryXactLock(): CommandError? {
    val lockTimeoutMilliseconds = 10_000
    try {
        exec("SET LOCAL lock_timeout = '${lockTimeoutMilliseconds}ms';")
        exec("SELECT pg_advisory_xact_lock(-1)")
    } catch (e: PSQLException) {
        if (e.message.orEmpty().contains("canceling statement due to lock timeout")) {
            return LockingError
        } else {
            throw e
        }
    }
    return null
}

interface EventTypeResolver {
    fun serialize(domainEventClass: Class<out DomainEvent>): String
    fun deserialize(aggregateType: String, eventType: String): Class<out DomainEvent>
}

object CanonicalNameEventTypeResolver : EventTypeResolver {
    override fun serialize(domainEventClass: Class<out DomainEvent>) = domainEventClass.canonicalName

    override fun deserialize(aggregateType: String, eventType: String) = eventType.asClass<DomainEvent>()!!
}