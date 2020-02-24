package eventsourcing

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS
import com.fasterxml.jackson.datatype.joda.JodaModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import database.jsonb
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.joda.time.DateTime
import java.util.*

class DatabaseEventStore private constructor(private val db: Database) : EventStore {
    companion object {
        fun create(db: Database): DatabaseEventStore {
            transaction(db) {
                // TODO don't do this if pointing directly to Murmur DB or potentially introduce separate migrations
                SchemaUtils.create(Events)
            }
            return DatabaseEventStore(db)
        }
    }

    override lateinit var listeners: List<EventListener>

    override fun sink(newEvents: List<Event>, aggregateId: UUID, aggregateType: String) {
        transaction(db) {
            newEvents.forEach { event ->
                Events.insert { row ->
                    row[Events.aggregateSequence] = event.aggregateSequence
                    row[Events.eventId] = event.id
                    row[Events.aggregateId] = aggregateId
                    row[Events.aggregateType] = aggregateType
                    row[Events.eventType] = event.domainEvent.javaClass.canonicalName
                    row[Events.createdAt] = DateTime.now()
                    row[Events.body] = om.writeValueAsString(event.domainEvent)
                    row[Events.metadata] = om.writeValueAsString(event.metadata)
                }
            }
        }
        notifyListeners(newEvents, aggregateId) // TODO should this be in the same transaction?
    }

    override fun eventsFor(aggregateId: UUID): List<Event> {
        return transaction(db) {
            return@transaction Events
                .select { Events.aggregateId eq aggregateId }
                .orderBy(Events.sequence)
                .map { row ->
                    val type = row[Events.eventType].asClass<DomainEvent>()
                    val domainEvent = om.readValue(row[Events.body], type)
                    val metadata = om.readValue(row[Events.metadata], Metadata::class.java)
                    Event(
                        id = row[Events.eventId],
                        aggregateId = aggregateId,
                        aggregateSequence = row[Events.aggregateSequence],
                        createdAt = row[Events.createdAt],
                        metadata = metadata,
                        domainEvent = domainEvent
                    )
                }
        }
    }

    override fun isTaken(aggregateId: UUID): Boolean {
        return transaction(db) {
            Events
                .select { Events.aggregateId eq aggregateId }
                .count() > 0
        }
    }

}

private fun <T> String.asClass(): Class<out T>? {
    return Class.forName(this) as Class<out T>?
}

val om = ObjectMapper().registerKotlinModule().registerModule(JodaModule()).configure(WRITE_DATES_AS_TIMESTAMPS, false)

object Events : Table() {
    val sequence = long("sequence").autoIncrement().index()
    val eventId = uuid("id")
    val aggregateSequence = long("aggregate_sequence").primaryKey(0)
    val aggregateId = uuid("aggregate_id").primaryKey(1)
    val aggregateType = varchar("aggregate_type", 128)
    val eventType = varchar("event_type", 128)
    val createdAt = date("createdAt")
    val body = jsonb("json_body")
    val metadata = jsonb("metadata")
}