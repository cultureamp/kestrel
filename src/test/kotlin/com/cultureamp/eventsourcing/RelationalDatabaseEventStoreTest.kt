package com.cultureamp.eventsourcing

import com.cultureamp.eventsourcing.sample.PizzaCreated
import com.cultureamp.eventsourcing.sample.PizzaEaten
import com.cultureamp.eventsourcing.sample.PizzaStyle
import com.cultureamp.eventsourcing.sample.PizzaTopping
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import org.joda.time.DateTime
import java.util.*

data class SimpleMetadata(val executor_id: String): EventMetadata()

class RelationalDatabaseEventStoreTest : DescribeSpec({
    val h2DbUrl = "jdbc:h2:mem:test;MODE=MySQL;DB_CLOSE_DELAY=-1;"
    val h2Driver = "org.h2.Driver"
    val db = Database.connect(url = h2DbUrl, driver = h2Driver)
    val table = H2DatabaseEventStore.eventsTable()
    val store = RelationalDatabaseEventStore.create(db)

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
            val domainEvents = listOf(
                PizzaCreated(PizzaStyle.MARGHERITA, listOf(PizzaTopping.TOMATO_PASTE, PizzaTopping.BASIL, PizzaTopping.CHEESE)),
                PizzaEaten()
            )
            val events = domainEvents.mapIndexed { index, de ->
                Event(UUID.randomUUID(), aggregateId, index.toLong(), DateTime.now(), SimpleMetadata("alice"), de)
            }
            val otherDomainEvents = listOf(
                PizzaCreated(PizzaStyle.MARGHERITA, listOf(PizzaTopping.TOMATO_PASTE, PizzaTopping.BASIL, PizzaTopping.CHEESE))
            )
            val otherEvents = domainEvents.mapIndexed { index, de ->
                Event(UUID.randomUUID(), aggregateId, index.toLong(), DateTime.now(), SimpleMetadata("bob"), de)
            }

            store.sink(events, aggregateId, "pizza")
            store.sink(otherEvents, otherAggregateId, "pizza")

            store.eventsFor(aggregateId) shouldBe events
        }
    }
})


