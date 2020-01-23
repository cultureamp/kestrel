package eventsourcing

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.jetbrains.exposed.dao.IntIdTable
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
                    row[Events.aggregateId] = aggregateId
                    row[Events.aggregateType] = aggregateType
                    row[Events.eventType] = event.javaClass.canonicalName
                    row[Events.date] = DateTime.now()
                    row[Events.body] = event.asJson()
                }
            }
        }
        notifyListeners(newEvents, aggregateId)
    }

    override fun eventsFor(aggregateId: UUID): Pair<CreationEvent, List<UpdateEvent>> {
        return transaction(db) {
            val events = Events
                .select { Events.aggregateId eq aggregateId }
                .orderBy(Events.id)
                .map { row ->
                    val type = row[Events.eventType].asClass<Event>()
                    om.readValue(row[Events.body], type)
                }
            val creationEvent = events.first() as CreationEvent
            val updateEvents = events.slice(1 until events.size).map {
                it as UpdateEvent
            }
            return@transaction creationEvent to updateEvents
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

private fun Event.asJson(): String {
    return om.writeValueAsString(this)
}

object Events : IntIdTable() {
    val aggregateId = uuid("aggregate_id")
    val aggregateType = varchar("aggregate_type", 128)
    val eventType = varchar("event_type", 128)
    val date = date("date")
    val body = text("json_body")
}