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
val defaultEventTypeResolver = CanonicalNameEventTypeResolver

class RelationalDatabaseEventStore<M : EventMetadata> @PublishedApi internal constructor(
    private val db: Database,
    val events: Events,
    val eventsSequenceStats: EventsSequenceStats?,
    private val metadataClass: Class<M>,
    private val objectMapper: ObjectMapper,
    private val eventTypeResolver: EventTypeResolver,
    private val blockingLockUntilTransactionEnd: Transaction.() -> CommandError? = { null },
    private val afterSinkHook: (List<SequencedEvent<M>>) -> Unit = { },
    private val eventsSinkTable: Events = events,
) : EventStore<M> {

    companion object {
        inline fun <reified M : EventMetadata> create(
            db: Database,
            objectMapper: ObjectMapper = defaultObjectMapper,
            eventsTableName: String = defaultEventsTableName,
            noinline afterSinkHook: (List<SequencedEvent<M>>) -> Unit = { },
            eventTypeResolver: EventTypeResolver = defaultEventTypeResolver,
            eventsSequenceStats: EventsSequenceStats? = RelationalDatabaseEventsSequenceStats(db, eventTypeResolver, defaultEventsSequenceStatsTableName).also { it.createSchemaIfNotExists() },
        ): RelationalDatabaseEventStore<M> {
            val jsonb: Table.(String) -> Column<String> = when (db.dialect) {
                is H2Dialect -> Table::text
                is PostgreSQLDialect -> Table::jsonb
                else -> throw UnsupportedOperationException("${db.dialect} not currently supported")
            }
            val blockingLockUntilTransactionEnd: Transaction.() -> CommandError? = when (db.dialect) {
                is H2Dialect -> { { null } }
                is PostgreSQLDialect -> Transaction::pgAdvisoryXactLock
                else -> throw UnsupportedOperationException("${db.dialect} not currently supported")
            }
            return RelationalDatabaseEventStore(db, Events(eventsTableName, jsonb), eventsSequenceStats, M::class.java, objectMapper, eventTypeResolver, blockingLockUntilTransactionEnd, afterSinkHook)
        }
    }

    fun createSchemaIfNotExists() {
        transaction(db) {
            SchemaUtils.create(events, eventsSinkTable)
        }
    }

    override fun sink(newEvents: List<Event<M>>, aggregateId: UUID): Either<CommandError, Long> {
        val sunkSequencedEvents = try {
            transaction(db) {
                blockingLockUntilTransactionEnd()?.let { Left(it) } ?: run {
                    newEvents.map { event ->
                        val body = objectMapper.writeValueAsString(event.domainEvent)
                        val domainEventClass = event.domainEvent.javaClass
                        val metadata = objectMapper.writeValueAsString(event.metadata)
                        validateSerialization(domainEventClass, body, metadata)
                        val eventType = eventTypeResolver.serialize(domainEventClass)
                        val insertResult = eventsSinkTable.insert { row ->
                            row[eventsSinkTable.aggregateSequence] = event.aggregateSequence
                            row[eventsSinkTable.eventId] = event.id
                            row[eventsSinkTable.aggregateId] = aggregateId
                            row[eventsSinkTable.aggregateType] = event.aggregateType
                            row[eventsSinkTable.eventType] = eventType
                            row[eventsSinkTable.createdAt] = event.createdAt
                            row[eventsSinkTable.body] = body
                            row[eventsSinkTable.metadata] = metadata
                        }
                        val dryRunMode = (events != eventsSinkTable)
                        if (dryRunMode) {
                            SequencedEvent(event, -1)
                        } else {
                            val insertedSequence = insertResult[eventsSinkTable.sequence]
                            eventsSequenceStats?.update(event.domainEvent::class, insertedSequence)
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
}

open class EventDataException(e: Exception) : Throwable(e)
class EventBodySerializationException(e: Exception) : EventDataException(e)
class EventMetadataSerializationException(e: Exception) : EventDataException(e)

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
