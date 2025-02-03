package com.cultureamp.eventsourcing

import com.cultureamp.eventsourcing.example.ParticipantAggregate
import com.cultureamp.eventsourcing.example.SimpleThingAggregate
import com.cultureamp.eventsourcing.example.ThingAggregate
import com.cultureamp.eventsourcing.sample.EatPizza
import com.cultureamp.eventsourcing.sample.PizzaAggregate
import com.cultureamp.eventsourcing.sample.PizzaCreated
import com.cultureamp.eventsourcing.sample.PizzaMustBeEatenByLastPersonWhoEdittedIt
import com.cultureamp.eventsourcing.sample.PizzaOnDifferentAccount
import com.cultureamp.eventsourcing.sample.PizzaStyle
import com.cultureamp.eventsourcing.sample.PizzaTopping
import com.cultureamp.eventsourcing.sample.PizzaToppingAdded
import com.cultureamp.eventsourcing.sample.PizzaUpdateCommand
import com.cultureamp.eventsourcing.sample.StandardEventMetadata
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

class AggregateTest : DescribeSpec({
    describe("aggregateType") {
        it("interface-based AggregateConstructors have a sane default aggregateType") {
            SimpleThingAggregate.aggregateType() shouldBe "SimpleThingAggregate"
        }

        it("interface-based AggregateConstructors can have a custom aggregateType") {
            ThingAggregate.aggregateType() shouldBe "thing"
        }

        it("function-based AggregateConstructors have a sane default aggregateType") {
            val aggregateConstructor = AggregateConstructor.from(
                ParticipantAggregate.Companion::create,
                ParticipantAggregate::update,
                ParticipantAggregate.Companion::created,
                ParticipantAggregate::updated
            )
            aggregateConstructor.aggregateType() shouldBe "ParticipantAggregate"
        }

        it("function-based AggregateConstructors can have a custom aggregateType") {
            val aggregateConstructor = AggregateConstructor.from(
                ParticipantAggregate.Companion::create,
                ParticipantAggregate::update,
                ParticipantAggregate.Companion::created,
                ParticipantAggregate::updated
            ) { "participant" }
            aggregateConstructor.aggregateType() shouldBe "participant"
        }

        it("stateless function-based AggregateConstructors have a sane default aggregateType") {
            val aggregateConstructor = AggregateConstructor.fromStateless(
                PaymentSagaAggregate::create,
                PaymentSagaAggregate::update,
                PaymentSagaAggregate
            )
            aggregateConstructor.aggregateType() shouldBe "PaymentSagaAggregate"
        }

        it("stateless function-based AggregateConstructors can have a custom aggregateType") {
            val aggregateConstructor = AggregateConstructor.fromStateless(
                PaymentSagaAggregate::create,
                PaymentSagaAggregate::update,
                PaymentSagaAggregate
            ) { "paymentSaga" }
            aggregateConstructor.aggregateType() shouldBe "paymentSaga"
        }

        it("aggregates can be constructed that accept metadata when rehydrating") {
            val aggregateConstructor = AggregateConstructor.from(
                PizzaAggregate.Companion::create,
                PizzaAggregate::update,
                ::PizzaAggregate,
                PizzaAggregate::updated
            )
            aggregateConstructor.aggregateType() shouldBe "PizzaAggregate"
            val metadata = StandardEventMetadata(accountId = UUID.randomUUID(), executorId = UUID.randomUUID())
            val pizzaAggregate = aggregateConstructor.created(PizzaCreated(PizzaStyle.HAWAIIAN, initialToppings = emptyList()), metadata)
            pizzaAggregate.update(EatPizza(UUID.randomUUID()), metadata.copy(accountId = UUID.randomUUID())) shouldBe Left(PizzaOnDifferentAccount)
            val secondMetadata = metadata.copy(executorId = UUID.randomUUID())
            val updatedPizza = pizzaAggregate.updated(PizzaToppingAdded(PizzaTopping.BASIL), secondMetadata) as Aggregate<PizzaUpdateCommand, *, *, StandardEventMetadata, *>
            updatedPizza.update(EatPizza(UUID.randomUUID()), metadata) shouldBe Left(PizzaMustBeEatenByLastPersonWhoEdittedIt)
        }
    }
})
