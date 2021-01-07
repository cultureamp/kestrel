package com.cultureamp.eventsourcing

import com.cultureamp.eventsourcing.sample.*
import com.cultureamp.eventsourcing.sample.PizzaStyle.MARGHERITA
import com.cultureamp.eventsourcing.sample.PizzaTopping.*
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import org.joda.time.DateTime
import java.util.*

class RelationalDatabaseEventStoreTest : DescribeSpec({
    val h2DbUrl = "jdbc:h2:mem:test;MODE=MySQL;DB_CLOSE_DELAY=-1;"
    val h2Driver = "org.h2.Driver"
    val db = Database.connect(url = h2DbUrl, driver = h2Driver)
    val tableName = "eventStore"
    val table = H2DatabaseEventStore.eventsTable(tableName)
    val store = RelationalDatabaseEventStore.create<StandardEventMetadata>(db, tableName = tableName)

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
            val basicPizzaCreated = PizzaCreated(MARGHERITA, listOf(TOMATO_PASTE, BASIL, CHEESE))
            val firstPizzaCreated = event(
                basicPizzaCreated,
                aggregateId,
                1,
                StandardEventMetadata("alice", "123")
            )
            val firstPizzaEaten = event(
                PizzaEaten(),
                aggregateId,
                2,
                StandardEventMetadata("alice")
            )
            val secondPizzaCreated = event(
                basicPizzaCreated,
                otherAggregateId,
                1,
                StandardEventMetadata("bob", "321")
            )

            val events = listOf(firstPizzaCreated, firstPizzaEaten)
            val otherEvents = listOf(secondPizzaCreated)

            store.sink(events, aggregateId) shouldBe Right(Unit)
            store.sink(otherEvents, otherAggregateId) shouldBe Right(Unit)

            store.eventsFor(aggregateId) shouldBe events
            store.eventsFor(otherAggregateId) shouldBe otherEvents

            val expectedSequenceEvents = (events + otherEvents).mapIndexed { seq, ev -> SequencedEvent(ev, (seq + 1).toLong()) }
            store.getAfter(0L) shouldBe expectedSequenceEvents
            store.getAfter(0L, listOf(PizzaEaten::class)).map { it.event } shouldBe listOf(firstPizzaEaten)
        }

        it("exposes the latest sequence value") {
            val aggregateId = UUID.randomUUID()
            val events = listOf(
                event(PizzaCreated(MARGHERITA, listOf(TOMATO_PASTE)), aggregateId, 1, StandardEventMetadata("unused")),
                event(PizzaEaten(), aggregateId, 3, StandardEventMetadata("unused")),
                event(PizzaToppingAdded(CHEESE), aggregateId, 2, StandardEventMetadata("unused"))
            )

            store.lastSequence() shouldBe 0
            store.sink(events, aggregateId) shouldBe Right(Unit)
            store.lastSequence() shouldBe 3
        }

        it("sends each sunk event to passed synchronous event-processors") {
            var count = 0
            val firstProjector: DomainEventProcessor<TestEvent> = object : DomainEventProcessor<TestEvent> {
                override fun process(event: TestEvent, aggregateId: UUID) {
                    count++
                }
            }
            val secondProjector: DomainEventProcessorWithMetadata<TestEvent, SpecificMetadata> = object : DomainEventProcessorWithMetadata<TestEvent, SpecificMetadata> {
                override fun process(event: TestEvent, aggregateId: UUID, metadata: SpecificMetadata, eventId: UUID) {
                    count++
                }
            }
            val firstEventProcessor = EventProcessor.from(firstProjector)
            val secondEventProcessor = EventProcessor.from(secondProjector)
            val synchronousEventProcessors = listOf(firstEventProcessor, secondEventProcessor)
            val fooDomainEvent = FooEvent("bar")
            val fooEvent = Event(
                id = UUID.randomUUID(),
                aggregateId = UUID.randomUUID(),
                aggregateSequence = 1,
                aggregateType = "aggregateType",
                createdAt = DateTime.now(),
                metadata = SpecificMetadata("specialField"),
                domainEvent = fooDomainEvent
            )
            val barDomainEvent = BarEvent("quux")
            val barEvent = Event(
                id = UUID.randomUUID(),
                aggregateId = UUID.randomUUID(),
                aggregateSequence = 2,
                aggregateType = "aggregateType",
                createdAt = DateTime.now(),
                metadata = SpecificMetadata("specialField"),
                domainEvent = barDomainEvent
            )


            val storeWithProjectors = RelationalDatabaseEventStore.create(synchronousEventProcessors, db, tableName = tableName)

            storeWithProjectors.sink(listOf(fooEvent, barEvent), UUID.randomUUID())

            count shouldBe 4
        }
    }
})

fun <M : EventMetadata>event(domainEvent: DomainEvent, aggregateId: UUID, index: Int, metadata: M): Event<M> {
    return Event(UUID.randomUUID(), aggregateId, index.toLong(), "pizza", DateTime.now(), metadata, domainEvent)
}


