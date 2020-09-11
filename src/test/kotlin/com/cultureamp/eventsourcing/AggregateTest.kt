package com.cultureamp.eventsourcing

import com.cultureamp.eventsourcing.fixtures.Invited
import com.cultureamp.eventsourcing.fixtures.ParticipantAggregate
import com.cultureamp.eventsourcing.fixtures.ThingAggregate
import com.cultureamp.eventsourcing.sample.PizzaAggregate
import com.cultureamp.eventsourcing.sample.PizzaStyle
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import org.joda.time.DateTime
import java.util.*

class AggregateTest : DescribeSpec({
    describe("aggregateType") {
        it("interface-based Aggregates have a sane default aggregateType") {
            ThingAggregate().aggregateType() shouldBe "ThingAggregate"
        }

        it("interface-based Aggregates can have a custom aggregateType") {
            PizzaAggregate(PizzaStyle.MARGHERITA, emptyList()).aggregateType() shouldBe "pizza"
        }

        it("function-based Aggregates have a sane default aggregateType") {
            val aggregateConstructor = AggregateConstructor.from(
                ParticipantAggregate.Companion::create,
                ParticipantAggregate::update,
                ParticipantAggregate.Companion::created,
                ParticipantAggregate::updated
            )
            val aggregate = aggregateConstructor.created(Invited(UUID.randomUUID(), UUID.randomUUID(), DateTime.now()))
            aggregate.aggregateType() shouldBe "ParticipantAggregate"
        }

        it("function-based Aggregates can have a custom aggregateType") {
            val aggregateConstructor = AggregateConstructor.from(
                ParticipantAggregate.Companion::create,
                ParticipantAggregate::update,
                ParticipantAggregate.Companion::created,
                ParticipantAggregate::updated
            ) { "participant" }
            val aggregate = aggregateConstructor.created(Invited(UUID.randomUUID(), UUID.randomUUID(), DateTime.now()))
            aggregate.aggregateType() shouldBe "participant"
        }

        it("stateless function-based Aggregates have a sane default aggregateType") {
            val aggregateConstructor = AggregateConstructor.fromStateless(
                PaymentSagaAggregate::create,
                PaymentSagaAggregate::update,
                PaymentSagaAggregate
            )
            val aggregate = aggregateConstructor.created(PaymentSagaStarted(UUID.randomUUID(), "details", 1, DateTime.now()))
            aggregate.aggregateType() shouldBe "PaymentSagaAggregate"
        }

        it("stateless function-based Aggregates can have a custom aggregateType") {
            val aggregateConstructor = AggregateConstructor.fromStateless(
                PaymentSagaAggregate::create,
                PaymentSagaAggregate::update,
                PaymentSagaAggregate
            ) { "paymentSaga" }
            val aggregate = aggregateConstructor.created(PaymentSagaStarted(UUID.randomUUID(), "details", 1, DateTime.now()))
            aggregate.aggregateType() shouldBe "paymentSaga"
        }
    }
})
