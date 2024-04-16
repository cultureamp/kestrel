package com.cultureamp.eventsourcing

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.core.type.TypeReference
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
import org.jetbrains.exposed.sql.json.jsonb
import org.jetbrains.exposed.sql.max
import org.jetbrains.exposed.sql.replace
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.vendors.H2Dialect
import org.jetbrains.exposed.sql.vendors.PostgreSQLDialect
import java.sql.SQLException
import java.util.UUID
import kotlin.reflect.KClass

val defaultObjectMapper = ObjectMapper()
    .registerModule(kotlinModule { singletonSupport(SingletonSupport.CANONICALIZE) })
    .registerModule(JodaModule())
    .configure(WRITE_DATES_AS_TIMESTAMPS, false)
    .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)

val defaultEventsTableName = "events"
val defaultEventsSinkTableName = "events_dry_run_sink"
val defaultEventsSequenceStatsTableName = "events_sequence_stats"
val defaultEventTypeResolver = CanonicalNameEventTypeResolver

class RelationalDatabaseEventStore<M : EventMetadata> @PublishedApi internal constructor(
    private val db: Database,
    internal val events: Events<M>,
    private val eventsSequenceStats: EventsSequenceStats,
    private val objectMapper: ObjectMapper,
    private val eventTypeResolver: EventTypeResolver,
    private val blockingLockUntilTransactionEnd: Transaction.() -> CommandError? = { null },
    private val afterSinkHook: (List<SequencedEvent<M>>) -> Unit = { },
    internal val eventsSinkTable: Events<M> = events,
) : EventStore<M> {

    companion object {
        inline fun <reified M : EventMetadata> create(
            db: Database,
            objectMapper: ObjectMapper = defaultObjectMapper,
            eventsTableName: String = defaultEventsTableName,
            eventsSequenceStateTableName: String = defaultEventsSequenceStatsTableName,
            eventTypeResolver: EventTypeResolver = defaultEventTypeResolver,
            noinline afterSinkHook: (List<SequencedEvent<M>>) -> Unit = { },
            eventsSinkTableName: String = eventsTableName,
        ): RelationalDatabaseEventStore<M> =
            when (db.dialect) {
                is H2Dialect -> H2DatabaseEventStore.create(
                    db,
                    objectMapper,
                    eventsTableName,
                    eventsSequenceStateTableName,
                    eventTypeResolver,
                    afterSinkHook,
                    eventsSinkTableName
                )

                is PostgreSQLDialect -> PostgresDatabaseEventStore.create(
                    db,
                    objectMapper,
                    eventsTableName,
                    eventsSequenceStateTableName,
                    eventTypeResolver,
                    afterSinkHook,
                    eventsSinkTableName
                )

                else -> throw UnsupportedOperationException("${db.dialect} not currently supported")
            }
        inline fun <reified M : EventMetadata> createDryRun(
            db: Database,
            objectMapper: ObjectMapper = defaultObjectMapper,
            eventsTableName: String = defaultEventsTableName,
            eventsSinkTableName: String = defaultEventsSinkTableName,
            eventsSequenceStateTableName: String = defaultEventsSequenceStatsTableName,
            eventTypeResolver: EventTypeResolver = defaultEventTypeResolver
        ) =
            create<M>(db, objectMapper, eventsTableName, eventsSequenceStateTableName, eventTypeResolver, {}, eventsSinkTableName)
    }

    fun createSchemaIfNotExists() {
        transaction(db) {
            SchemaUtils.create(events, eventsSequenceStats, eventsSinkTable)
        }
    }

    override fun sink(newEvents: List<Event<M>>, aggregateId: UUID): Either<CommandError, Long> {
        val sunkSequencedEvents = try {
            transaction(db) {
                blockingLockUntilTransactionEnd()?.let { Left(it) } ?: run {
                    newEvents.map { event ->
                        val body: Map<String, Any> = objectMapper.convertValue(event.domainEvent, object : TypeReference<Map<String, Any>>() {})
                        val domainEventClass = event.domainEvent.javaClass
                        val eventType = eventTypeResolver.serialize(domainEventClass)
                        val insertResult = eventsSinkTable.insert { row ->
                            row[eventsSinkTable.aggregateSequence] = event.aggregateSequence
                            row[eventsSinkTable.eventId] = event.id
                            row[eventsSinkTable.aggregateId] = aggregateId
                            row[eventsSinkTable.aggregateType] = event.aggregateType
                            row[eventsSinkTable.eventType] = eventType
                            row[eventsSinkTable.createdAt] = event.createdAt
                            row[eventsSinkTable.body] = body
                            row[eventsSinkTable.metadata] = event.metadata
                        }
                        val dryRunMode = (events != eventsSinkTable)
                        if (dryRunMode) {
                            SequencedEvent(event, -1)
                        } else {
                            val insertedSequence = insertResult[eventsSinkTable.sequence]
                            eventsSequenceStats.replace {
                                it[eventsSequenceStats.eventType] = eventType
                                it[eventsSequenceStats.sequence] = insertedSequence
                            }
                            SequencedEvent(event, insertedSequence)
                        }
                    }.let { Right(it) }
                }
            }
        } catch (e: ExposedSQLException) {
            if (e.message.orEmpty().contains("violates unique constraint") || e.message.orEmpty().contains("Unique index or primary key violation")) {
                Left(ConcurrencyError)
            } else {
                throw e
            }
        }
        return sunkSequencedEvents.map {
            afterSinkHook(it)
            it.last().sequence
        }
    }

    private fun rowToSequencedEvent(row: ResultRow): SequencedEvent<M> = row.let {
        val eventType = eventTypeResolver.deserialize(row[events.aggregateType], row[events.eventType])
        val domainEvent = objectMapper.convertValue(row[events.body], eventType)
        val metadata = row[events.metadata]

        SequencedEvent(
            Event(
                id = row[events.eventId],
                aggregateId = row[events.aggregateId],
                aggregateSequence = row[events.aggregateSequence],
                aggregateType = row[events.aggregateType],
                createdAt = row[events.createdAt],
                metadata = metadata,
                domainEvent = domainEvent,
            ),
            row[events.sequence],
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

class EventDataException(e: Exception) : Throwable(e)

object PostgresDatabaseEventStore {
    @PublishedApi
    internal inline fun <reified M : EventMetadata> create(
        db: Database,
        objectMapper: ObjectMapper,
        tableName: String,
        eventsSequenceStateTableName: String,
        eventTypeResolver: EventTypeResolver,
        noinline afterSinkHook: (List<SequencedEvent<M>>) -> Unit,
        eventsSinkTableName: String,
    ): RelationalDatabaseEventStore<M> {
        return RelationalDatabaseEventStore(
            db,
            Events(tableName, objectMapper, object : TypeReference<M>() {}),
            EventsSequenceStats(eventsSequenceStateTableName),
            objectMapper,
            eventTypeResolver,
            Transaction::pgAdvisoryXactLock,
            afterSinkHook,
            Events(eventsSinkTableName, objectMapper, object : TypeReference<M>() {}),
        )
    }
}

object H2DatabaseEventStore {
    // need a `@PublishedApi` here to make it callable from `RelationalDatabaseEventStore.create()`
    @PublishedApi
    internal inline fun <reified M : EventMetadata> create(
        db: Database,
        objectMapper: ObjectMapper,
        tableName: String,
        eventsSequenceStateTableName: String,
        eventTypeResolver: EventTypeResolver,
        noinline afterSinkHook: (List<SequencedEvent<M>>) -> Unit,
        eventsSinkTableName: String,
    ): RelationalDatabaseEventStore<M> {
        return RelationalDatabaseEventStore(
            db,
            Events(tableName, objectMapper, object : TypeReference<M>() {}),
            EventsSequenceStats(eventsSequenceStateTableName),
            objectMapper,
            eventTypeResolver,
            afterSinkHook = afterSinkHook,
            eventsSinkTable = Events(eventsSinkTableName, objectMapper, object : TypeReference<M>() {}),
        )
    }
}

internal fun <T> String.asClass(): Class<out T>? {
    @Suppress("UNCHECKED_CAST")
    return Class.forName(this) as Class<out T>?
}

class Events<M : EventMetadata>(tableName: String = defaultEventsTableName, private val objectMapper: ObjectMapper = defaultObjectMapper, metadataType: TypeReference<M>) : Table(tableName) {
    private val mapType = object : TypeReference<Map<String, Any>>() {}

    val sequence = long("sequence").autoIncrement()
    val eventId = uuid("id")
    val aggregateSequence = long("aggregate_sequence")
    val aggregateId = uuid("aggregate_id")
    val aggregateType = varchar("aggregate_type", 128)
    val eventType = varchar("event_type", 256)
    val createdAt = datetime("created_at")
    val body = jsonb("json_body", { validatedSerialize(it, mapType) }, { deserialize(it, mapType) })
    val metadata = jsonb("metadata", { validatedSerialize(it, metadataType) }, { deserialize(it, metadataType) })
    override val primaryKey: PrimaryKey = PrimaryKey(sequence)

    init {
        uniqueIndex(eventId)
        uniqueIndex(aggregateId, aggregateSequence)
        nonUniqueIndex(eventType, aggregateType)
    }

    private fun <T> validatedSerialize(value: Any, type: TypeReference<T>): String {
        val jsonString = objectMapper.writeValueAsString(value)
        // prove that object can be deserialized, which catches invalid fields types, e.g. interfaces
        deserialize(jsonString, type)
        return jsonString
    }

    private fun <T> deserialize(jsonString: String?, type: TypeReference<T>): T {
        return try {
            objectMapper.readValue(jsonString, type)
        } catch (e: JsonProcessingException) {
            throw EventDataException(e)
        }
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
    } catch (e: SQLException) {
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
