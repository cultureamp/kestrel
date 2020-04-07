package com.cultureamp.eventsourcing

import com.cultureamp.eventsourcing.sample.*
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.*

class EmptyMetadata() : EventMetadata()

class CommandGatewayIntegrationTest : DescribeSpec({
    val h2DbUrl = "jdbc:h2:mem:test;MODE=MySQL;DB_CLOSE_DELAY=-1;"
    val h2Driver = "org.h2.Driver"
    val db = Database.connect(url = h2DbUrl, driver = h2Driver)
    val eventsTable = H2DatabaseEventStore.eventsTable()
    val eventStore = RelationalDatabaseEventStore.create(db)
    val registry = listOf(
        Configuration.from(PizzaAggregate)
    )
    val gateway = CommandGateway(eventStore, registry)

    afterTest {
        transaction(db) {
            SchemaUtils.drop(eventsTable)
        }
    }

    beforeTest {
        eventStore.setUp()
    }


    describe("CommandGateway") {
        it("accepts a creation event") {
            val result = gateway.dispatch(CreateClassicPizza(UUID.randomUUID(), PizzaStyle.MARGHERITA), EmptyMetadata())
            result shouldBe Right(Created)
            transaction(db) {
                eventsTable.selectAll().count() shouldBe 1
            }
        }

        it("fails on creation with duplicate UUIDs") {
            val aggregateId = UUID.randomUUID()
            val result = gateway.dispatch(CreateClassicPizza(aggregateId, PizzaStyle.MARGHERITA), EmptyMetadata())
            result shouldBe Right(Created)
            val result2 = gateway.dispatch(CreateClassicPizza(aggregateId, PizzaStyle.HAWAIIAN), EmptyMetadata())
            result2 shouldBe Left(AggregateAlreadyExists)
            transaction(db) {
                eventsTable.selectAll().count() shouldBe 1
            }
        }

        it("accepts a creation then update event") {
            val aggregateId = UUID.randomUUID()
            val result = gateway.dispatch(CreateClassicPizza(aggregateId, PizzaStyle.MARGHERITA), EmptyMetadata())
            result shouldBe Right(Created)
            val result2 = gateway.dispatch(AddTopping(aggregateId, PizzaTopping.PINEAPPLE), EmptyMetadata())
            result2 shouldBe Right(Updated)
            transaction(db) {
                eventsTable.selectAll().count() shouldBe 2
            }
        }

        it("rejects command when invalid event sequence is provided") {
            val aggregateId = UUID.randomUUID()
            val result = gateway.dispatch(CreateClassicPizza(aggregateId, PizzaStyle.MARGHERITA), EmptyMetadata())
            result shouldBe Right(Created)
            val result2 = gateway.dispatch(EatPizza(aggregateId), EmptyMetadata())
            result2 shouldBe Right(Updated)
            val result3 = gateway.dispatch(AddTopping(aggregateId, PizzaTopping.PINEAPPLE), EmptyMetadata())
            result3.shouldBeInstanceOf<Left<PizzaAlreadyEaten>>()
            transaction(db) {
                eventsTable.selectAll().count() shouldBe 2
            }
        }

        it("fails on updating with unknown UUID") {
            val aggregateId = UUID.randomUUID()
            val result1 = gateway.dispatch(AddTopping(aggregateId, PizzaTopping.PINEAPPLE), EmptyMetadata())
            result1 shouldBe Left(AggregateNotFound)
            transaction(db) {
                eventsTable.selectAll().count() shouldBe 0
            }
        }
    }
})


