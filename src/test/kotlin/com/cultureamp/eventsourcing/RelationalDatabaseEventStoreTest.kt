package com.cultureamp.eventsourcing

import com.cultureamp.eventsourcing.sample.PizzaCreated
import com.cultureamp.eventsourcing.sample.PizzaEaten
import com.cultureamp.eventsourcing.sample.PizzaStyle.MARGHERITA
import com.cultureamp.eventsourcing.sample.PizzaTopping.BASIL
import com.cultureamp.eventsourcing.sample.PizzaTopping.CHEESE
import com.cultureamp.eventsourcing.sample.PizzaTopping.TOMATO_PASTE
import com.cultureamp.eventsourcing.sample.PizzaToppingAdded
import com.cultureamp.eventsourcing.sample.StandardEventMetadata
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.joda.time.DateTime
import java.util.UUID

class RelationalDatabaseEventStoreTest : DescribeSpec({
    val db = PgTestConfig.db ?: Database.connect(url = "jdbc:h2:mem:test;MODE=MySQL;DB_CLOSE_DELAY=-1;", driver = "org.h2.Driver")
    val tableName = "eventStore"
    val table = if (PgTestConfig.db != null) Events(tableName) else H2DatabaseEventStore.eventsTable(tableName)
    val eventsSequenceStats = EventsSequenceStats()
    val store = RelationalDatabaseEventStore.create<StandardEventMetadata>(db, eventsTableName = "eventStore")

    beforeTest {
        transaction(db) {
            SchemaUtils.create(table)
            SchemaUtils.create(eventsSequenceStats)
        }
    }

    afterTest {
        transaction(db) {
            SchemaUtils.drop(table)
            SchemaUtils.drop(eventsSequenceStats)
        }
    }

    val firstAccountId = UUID.randomUUID()
    val secondAccountId = UUID.randomUUID()
    val firstExecutorId = UUID.randomUUID()
    val secondExecutorId = UUID.randomUUID()

    describe("RelationalDatabaseEventStore") {
        it("sets and retrieves multiple events") {
            val aggregateId = UUID.randomUUID()
            val otherAggregateId = UUID.randomUUID()
            val basicPizzaCreated = PizzaCreated(MARGHERITA, listOf(TOMATO_PASTE, BASIL, CHEESE))
            val firstPizzaCreated = event(
                basicPizzaCreated,
                aggregateId,
                1,
                StandardEventMetadata(firstAccountId, firstExecutorId),
            )
            val firstPizzaEaten = event(
                PizzaEaten(),
                aggregateId,
                2,
                StandardEventMetadata(firstAccountId),
            )
            val secondPizzaCreated = event(
                basicPizzaCreated,
                otherAggregateId,
                1,
                StandardEventMetadata(secondAccountId, secondExecutorId),
            )

            val events = listOf(firstPizzaCreated, firstPizzaEaten)
            val otherEvents = listOf(secondPizzaCreated)

            store.sink(events, aggregateId) shouldBe Right(2L)
            store.sink(otherEvents, otherAggregateId) shouldBe Right(3L)

            store.eventsFor(aggregateId) shouldBe events
            store.eventsFor(otherAggregateId) shouldBe otherEvents

            val expectedSequenceEvents = (events + otherEvents).mapIndexed { seq, ev -> SequencedEvent(ev, (seq + 1).toLong()) }
            store.getAfter(0L) shouldBe expectedSequenceEvents
            store.getAfter(0L, listOf(PizzaEaten::class)).map { it.event } shouldBe listOf(firstPizzaEaten)
        }

        it("exposes the latest sequence value") {
            val aggregateId = UUID.randomUUID()
            val events = listOf(
                event(PizzaCreated(MARGHERITA, listOf(TOMATO_PASTE)), aggregateId, 1, StandardEventMetadata(firstAccountId)),
                event(PizzaEaten(), aggregateId, 3, StandardEventMetadata(firstAccountId)),
                event(PizzaToppingAdded(CHEESE), aggregateId, 2, StandardEventMetadata(firstAccountId)),
            )

            store.lastSequence() shouldBe 0
            store.sink(events, aggregateId) shouldBe Right(3L)
            store.lastSequence() shouldBe 3
            store.lastSequence(listOf(PizzaCreated::class)) shouldBe 1
            store.lastSequence(listOf(PizzaEaten::class)) shouldBe 2
            store.lastSequence(listOf(PizzaCreated::class, PizzaEaten::class)) shouldBe 2
        }

        it("gets the concurrency error from the sink") {
            val aggregateId = UUID.randomUUID()
            val events = listOf(
                event(PizzaCreated(MARGHERITA, listOf(TOMATO_PASTE)), aggregateId, 1, StandardEventMetadata(/* unused */UUID.randomUUID())),
                event(PizzaEaten(), aggregateId, 1, StandardEventMetadata(/* unused */UUID.randomUUID())),
            )
            store.sink(events, aggregateId) shouldBe Left(ConcurrencyError)
        }

        it("sends each sunk event to after-sink hook") {
            val fooDomainEvent = FooEvent("bar")
            val fooEvent = Event(
                id = UUID.randomUUID(),
                aggregateId = UUID.randomUUID(),
                aggregateSequence = 1,
                aggregateType = "aggregateType",
                createdAt = DateTime.now(),
                metadata = SpecificMetadata("specialField"),
                domainEvent = fooDomainEvent,
            )
            val barDomainEvent = BarEvent("quux")
            val barEvent = Event(
                id = UUID.randomUUID(),
                aggregateId = UUID.randomUUID(),
                aggregateSequence = 2,
                aggregateType = "aggregateType",
                createdAt = DateTime.now(),
                metadata = SpecificMetadata("specialField"),
                domainEvent = barDomainEvent,
            )
            val capturedEvents = mutableListOf<SequencedEvent<SpecificMetadata>>()
            val afterSinkHook: (List<SequencedEvent<SpecificMetadata>>) -> Unit = {
                capturedEvents.addAll(it)
            }

            val storeWithAfterSinkHook = RelationalDatabaseEventStore.create(db, eventsTableName = tableName, afterSinkHook = afterSinkHook)

            storeWithAfterSinkHook.sink(listOf(fooEvent, barEvent), UUID.randomUUID())

            capturedEvents shouldBe listOf(
                SequencedEvent(fooEvent, 1L),
                SequencedEvent(barEvent, 2L),
            )
        }

        it("sinks event even if the after-sink hook throws an exception") {
            val fooDomainEvent = FooEvent("bar")
            val fooEvent = Event(
                id = UUID.randomUUID(),
                aggregateId = UUID.randomUUID(),
                aggregateSequence = 1,
                aggregateType = "aggregateType",
                createdAt = DateTime.now(),
                metadata = SpecificMetadata("specialField"),
                domainEvent = fooDomainEvent,
            )
            val alwaysFailsAfterSinkHook: (List<SequencedEvent<SpecificMetadata>>) -> Unit = {
                throw IllegalStateException("expected")
            }
            val storeWithAfterSinkHook = RelationalDatabaseEventStore.create(db, eventsTableName = tableName, afterSinkHook = alwaysFailsAfterSinkHook)
            val exception = shouldThrow<IllegalStateException> {
                storeWithAfterSinkHook.sink(listOf(fooEvent), fooEvent.aggregateId)
            }
            exception.message shouldBe "expected"
            storeWithAfterSinkHook.eventsFor(fooEvent.aggregateId) shouldBe listOf(fooEvent)
        }

        it("allows providing a custom event type resolver") {
            val customEventTypeResolver = object : EventTypeResolver {
                override fun serialize(domainEventClass: Class<out DomainEvent>) = "custom.${domainEventClass.canonicalName}"

                override fun deserialize(aggregateType: String, eventType: String) = eventType.substringAfter("custom.").asClass<DomainEvent>()!!
            }
            val customStore = RelationalDatabaseEventStore.create<StandardEventMetadata>(db, eventsTableName = "eventStore", eventTypeResolver = customEventTypeResolver)
            val aggregateId = UUID.randomUUID()
            val pizzaCreatedDomainEvent = PizzaCreated(MARGHERITA, listOf(TOMATO_PASTE, BASIL, CHEESE))
            val pizzaCreatedEvent = event(
                pizzaCreatedDomainEvent,
                aggregateId,
                1,
                StandardEventMetadata(firstAccountId, firstExecutorId),
            )

            val events = listOf(pizzaCreatedEvent)
            customStore.sink(events, aggregateId) shouldBe Right(1L)
            customStore.eventsFor(aggregateId) shouldBe events

            transaction(db) {
                table.selectAll().map { it[table.eventType] shouldBe "custom.com.cultureamp.eventsourcing.sample.PizzaCreated" }
            }
        }
    }
})

fun <M : EventMetadata> event(domainEvent: DomainEvent, aggregateId: UUID, index: Int, metadata: M): Event<M> {
    return Event(UUID.randomUUID(), aggregateId, index.toLong(), "pizza", DateTime.now(), metadata, domainEvent)
}
