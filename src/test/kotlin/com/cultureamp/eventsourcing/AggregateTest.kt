package com.cultureamp.eventsourcing

import com.cultureamp.eventsourcing.example.ParticipantAggregate
import com.cultureamp.eventsourcing.example.SimpleThingAggregate
import com.cultureamp.eventsourcing.example.ThingAggregate
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe

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
    }
})
