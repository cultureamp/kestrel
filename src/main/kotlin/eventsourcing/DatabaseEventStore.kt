package eventsourcing

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.jetbrains.exposed.dao.LongIdTable
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
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
        notifyListeners(newEvents, aggregateId)
    }

    override fun eventsFor(aggregateId: UUID): List<Event> {
        return transaction(db) {
            return@transaction Events
                .select { Events.aggregateId eq aggregateId }
                .orderBy(Events.id)
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

val om = ObjectMapper().registerKotlinModule()

object Events : LongIdTable(columnName = "sequence") {
    val aggregateSequence = long("aggregate_sequence")
    val eventId = uuid("id")
    val aggregateId = uuid("aggregate_id")
    val aggregateType = varchar("aggregate_type", 128)
    val eventType = varchar("event_type", 128)
    val createdAt = date("createdAt")
    // TODO make this jsonb similar to https://gist.github.com/quangIO/a623b5caa53c703e252d858f7a806919
    val body = text("json_body")
    val metadata = text("metadata")
}