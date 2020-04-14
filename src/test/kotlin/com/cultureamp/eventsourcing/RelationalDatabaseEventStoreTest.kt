package com.cultureamp.eventsourcing

import com.cultureamp.eventsourcing.sample.*
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import org.joda.time.DateTime
import org.junit.jupiter.api.assertThrows
import java.util.*

class RelationalDatabaseEventStoreTest : DescribeSpec({
    val h2DbUrl = "jdbc:h2:mem:test;MODE=MySQL;DB_CLOSE_DELAY=-1;"
    val h2Driver = "org.h2.Driver"
    val db = Database.connect(url = h2DbUrl, driver = h2Driver)
    val table = H2DatabaseEventStore.eventsTable()
    val store =  RelationalDatabaseEventStore.create<StandardEventMetadata>(db)

    beforeTest {
        transaction(db) {
            SchemaUtils.create(table)
        }
    }

    afterTest {
        transaction(db) {
            SchemaUtils.drop(table)
        }
    }

    describe("RelationalDatabaseEventStore") {
        it("sets and retrieves multiple events") {
            val aggregateId = UUID.randomUUID()
            val otherAggregateId = UUID.randomUUID()
            val basicPizzaCreated = PizzaCreated(
                PizzaStyle.MARGHERITA,
                listOf(PizzaTopping.TOMATO_PASTE, PizzaTopping.BASIL, PizzaTopping.CHEESE)
            )
            val events = listOf(
                event(
                    basicPizzaCreated,
                    aggregateId,
                    1,
                    PizzaCreationEventMetadata("alice", "123")
                ),
                event(
                    PizzaEaten(),
                    aggregateId,
                    2,
                    StandardEventMetadata("alice")
                )
            )
            val otherEvents = listOf(
                event(
                    basicPizzaCreated,
                    otherAggregateId,
                    1,
                    PizzaCreationEventMetadata("bob", "321")
                )
            )

            store.sink(events, aggregateId, "pizza") shouldBe Right(Unit)
            store.sink(otherEvents, otherAggregateId, "pizza") shouldBe Right(Unit)

            store.eventsFor(aggregateId) shouldBe events
            store.eventsFor(otherAggregateId) shouldBe otherEvents

            val expectedSequenceEvents = (events + otherEvents).mapIndexed { seq, ev -> SequencedEvent(ev, (seq + 1).toLong()) }
            store.getAfter(0L, 100) shouldBe expectedSequenceEvents
        }

        it ("fails when non-default metadata is passed in and event uses default") {
            val aggregateId = UUID.randomUUID()
            val events = listOf(
                event(
                    PizzaToppingAdded(PizzaTopping.HAM),
                    aggregateId,
                    1,
                    EmptyMetadata()
                )
            )

            assertThrows<EventMetadataSerializationException> {
                store.sink(events, aggregateId, "pizza")
            }
        }
        
        it ("fails when invalid metadata for the event is passed in") {
            val aggregateId = UUID.randomUUID()
            val basicPizzaCreated = PizzaCreated(
                PizzaStyle.MARGHERITA,
                listOf(PizzaTopping.TOMATO_PASTE, PizzaTopping.BASIL, PizzaTopping.CHEESE)
            )
            val events = listOf(
                event(
                    basicPizzaCreated,
                    aggregateId,
                    1,
                    StandardEventMetadata("invalid_metadata")
                )
            )

            assertThrows<EventMetadataSerializationException> {
                store.sink(events, aggregateId, "pizza")
            }
        }
    }
})

fun event(domainEvent: DomainEvent, aggregateId: UUID, index: Int, metadata: EventMetadata): Event {
    return Event(UUID.randomUUID(), aggregateId, index.toLong(), DateTime.now(), metadata, domainEvent)
}


