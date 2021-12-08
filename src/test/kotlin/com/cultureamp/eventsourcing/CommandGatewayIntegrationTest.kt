package com.cultureamp.eventsourcing

import com.cultureamp.eventsourcing.example.AddSection
import com.cultureamp.eventsourcing.example.AlwaysBoppable
import com.cultureamp.eventsourcing.example.Boop
import com.cultureamp.eventsourcing.example.BoopWithProjection
import com.cultureamp.eventsourcing.example.Bop
import com.cultureamp.eventsourcing.example.Create
import com.cultureamp.eventsourcing.example.CreateSimpleThing
import com.cultureamp.eventsourcing.example.CreateSimpleThingWithProjection
import com.cultureamp.eventsourcing.example.CreateSurvey
import com.cultureamp.eventsourcing.example.CreateThing
import com.cultureamp.eventsourcing.example.Delete
import com.cultureamp.eventsourcing.example.Generate
import com.cultureamp.eventsourcing.example.IntendedPurpose
import com.cultureamp.eventsourcing.example.Invite
import com.cultureamp.eventsourcing.example.Locale
import com.cultureamp.eventsourcing.example.LocalizedText
import com.cultureamp.eventsourcing.example.ParticipantAggregate
import com.cultureamp.eventsourcing.example.PositionQuestion
import com.cultureamp.eventsourcing.example.RandomNumberGenerator
import com.cultureamp.eventsourcing.example.RemoveSection
import com.cultureamp.eventsourcing.example.SectionNotFound
import com.cultureamp.eventsourcing.example.SimpleThingAggregate
import com.cultureamp.eventsourcing.example.SimpleThingWithProjectionAggregate
import com.cultureamp.eventsourcing.example.StartCreatingSurvey
import com.cultureamp.eventsourcing.example.SurveyAggregate
import com.cultureamp.eventsourcing.example.SurveyCaptureLayoutAggregate
import com.cultureamp.eventsourcing.example.SurveyNameAlwaysAvailable
import com.cultureamp.eventsourcing.example.SurveySagaAggregate
import com.cultureamp.eventsourcing.example.ThingAggregate
import com.cultureamp.eventsourcing.example.Tweak
import com.cultureamp.eventsourcing.example.Twerk
import com.cultureamp.eventsourcing.example.TwerkWithProjection
import com.cultureamp.eventsourcing.example.Uninvite
import com.cultureamp.eventsourcing.sample.AddTopping
import com.cultureamp.eventsourcing.sample.CreateClassicPizza
import com.cultureamp.eventsourcing.sample.EatPizza
import com.cultureamp.eventsourcing.sample.PizzaAggregate
import com.cultureamp.eventsourcing.sample.PizzaAlreadyEaten
import com.cultureamp.eventsourcing.sample.PizzaStyle
import com.cultureamp.eventsourcing.sample.PizzaTopping
import com.cultureamp.eventsourcing.sample.StandardEventMetadata
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.joda.time.DateTime
import java.util.*
import com.cultureamp.eventsourcing.example.Created as SurveyCreated

class CommandGatewayIntegrationTest : DescribeSpec({
    val db = PgTestConfig.db ?: Database.connect(url = "jdbc:h2:mem:test;MODE=MySQL;DB_CLOSE_DELAY=-1;", driver = "org.h2.Driver")
    val table = Events()
    val tableH2 = H2DatabaseEventStore.eventsTable()
    val eventsTable = if(PgTestConfig.db != null) table else tableH2
    val eventStore = RelationalDatabaseEventStore.create<StandardEventMetadata>(db)
    val gateway = EventStoreCommandGateway(
        eventStore,
        Route.from(
            PizzaAggregate.Companion::create,
            PizzaAggregate::update,
            ::PizzaAggregate,
            PizzaAggregate::updated,
            PizzaAggregate.Companion::aggregateType
        ),
        Route.from(ThingAggregate.partial(AlwaysBoppable)),
        Route.from(SimpleThingAggregate),
        Route.from(SimpleThingWithProjectionAggregate.partial(RandomNumberGenerator())),
        Route.fromStateless(
            PaymentSagaAggregate::create,
            PaymentSagaAggregate::update,
            PaymentSagaAggregate
        ),
        Route.from(
            SurveyCaptureLayoutAggregate.Companion::create,
            SurveyCaptureLayoutAggregate::update,
            SurveyCaptureLayoutAggregate.Companion::created,
            SurveyCaptureLayoutAggregate::updated
        ),
        Route.from(
            SurveyAggregate.Companion::create.partial(SurveyNameAlwaysAvailable),
            SurveyAggregate::update.partial2(SurveyNameAlwaysAvailable),
            ::SurveyAggregate,
            SurveyAggregate::updated
        ),
        Route.from(
            SurveySagaAggregate.Companion::create,
            SurveySagaAggregate::update,
            ::SurveySagaAggregate
        ),
        Route.from(
            ParticipantAggregate.Companion::create,
            ParticipantAggregate::update,
            ParticipantAggregate.Companion::created,
            ParticipantAggregate::updated
        )
    )

    afterTest {
        transaction(db) {
            SchemaUtils.drop(eventsTable)
        }
    }

    beforeTest {
        eventStore.createSchemaIfNotExists()
    }


    val metadata = StandardEventMetadata("alice", "123")

    describe("CommandGateway") {
        it("accepts a creation event") {
            val result = gateway.dispatch(CreateClassicPizza(UUID.randomUUID(), PizzaStyle.MARGHERITA), metadata)
            result shouldBe Right(Created)
            transaction(db) {
                eventsTable.selectAll().count() shouldBe 1
            }
        }

        it("fails on creation with duplicate UUIDs") {
            val aggregateId = UUID.randomUUID()
            val result = gateway.dispatch(CreateClassicPizza(aggregateId, PizzaStyle.MARGHERITA), metadata)
            result shouldBe Right(Created)
            val result2 = gateway.dispatch(CreateClassicPizza(aggregateId, PizzaStyle.HAWAIIAN), metadata)
            result2 shouldBe Left(AggregateAlreadyExists)
            transaction(db) {
                eventsTable.selectAll().count() shouldBe 1
            }
        }

        it("accepts a creation then update event") {
            val aggregateId = UUID.randomUUID()
            val result = gateway.dispatch(CreateClassicPizza(aggregateId, PizzaStyle.MARGHERITA), metadata)
            result shouldBe Right(Created)
            val result2 = gateway.dispatch(AddTopping(aggregateId, PizzaTopping.PINEAPPLE), StandardEventMetadata("alice"))
            result2 shouldBe Right(Updated)
            transaction(db) {
                eventsTable.selectAll().count() shouldBe 2
            }
        }

        it("rejects command when invalid event sequence is provided") {
            val aggregateId = UUID.randomUUID()
            val result = gateway.dispatch(CreateClassicPizza(aggregateId, PizzaStyle.MARGHERITA), metadata)
            result shouldBe Right(Created)
            val result2 = gateway.dispatch(EatPizza(aggregateId), StandardEventMetadata("alice"))
            result2 shouldBe Right(Updated)
            val result3 = gateway.dispatch(AddTopping(aggregateId, PizzaTopping.PINEAPPLE), metadata)
            result3.shouldBeInstanceOf<Left<PizzaAlreadyEaten>>()
            transaction(db) {
                eventsTable.selectAll().count() shouldBe 2
            }
        }

        it("fails on updating with unknown UUID") {
            val aggregateId = UUID.randomUUID()
            val result1 = gateway.dispatch(AddTopping(aggregateId, PizzaTopping.PINEAPPLE), metadata)
            result1 shouldBe Left(AggregateNotFound)
            transaction(db) {
                eventsTable.selectAll().count() shouldBe 0
            }
        }

        it("can route to an aggregate created with function handles") {
            val pizzaId = UUID.randomUUID()
            gateway.dispatch(CreateClassicPizza(pizzaId, PizzaStyle.MARGHERITA), metadata) shouldBe Right(Created)
            gateway.dispatch(AddTopping(pizzaId, PizzaTopping.PINEAPPLE), metadata) shouldBe Right(Updated)

            transaction(db) {
                eventsTable.selectAll().count() shouldBe 2
            }
        }

        it("can route to a simple aggregate") {
            val simpleThingId = UUID.randomUUID()
            gateway.dispatch(CreateSimpleThing(simpleThingId), metadata) shouldBe Right(Created)
            gateway.dispatch(Boop(simpleThingId), metadata) shouldBe Right(Updated)
            gateway.dispatch(Twerk(simpleThingId, "dink"), metadata) shouldBe Right(Updated)

            transaction(db) {
                eventsTable.selectAll().count() shouldBe 3
            }
        }

        it("can route to a simple aggregate with a projection") {
            val simpleThingId = UUID.randomUUID()
            gateway.dispatch(CreateSimpleThingWithProjection(simpleThingId), metadata) shouldBe Right(Created)
            gateway.dispatch(BoopWithProjection(simpleThingId), metadata) shouldBe Right(Updated)
            gateway.dispatch(TwerkWithProjection(simpleThingId, "dink"), metadata) shouldBe Right(Updated)

            transaction(db) {
                eventsTable.selectAll().count() shouldBe 3
            }
        }

        it("can route to an aggregate with a projection wired into it") {
            val thingId = UUID.randomUUID()
            gateway.dispatch(CreateThing(thingId), metadata) shouldBe Right(Created)
            gateway.dispatch(Bop(thingId), metadata) shouldBe Right(Updated)
            gateway.dispatch(Tweak(thingId, "donk"), metadata) shouldBe Right(Updated)

            transaction(db) {
                eventsTable.selectAll().count() shouldBe 3
            }
        }

        it("can route to a stateless aggregate") {
            val paymentSagaId = UUID.randomUUID()
            gateway.dispatch(StartPaymentSaga(paymentSagaId, UUID.randomUUID(), "bank details", 42), metadata) shouldBe Right(Created)
            gateway.dispatch(StartThirdPartyPayment(paymentSagaId, DateTime.now()), metadata) shouldBe Right(Updated)

            transaction(db) {
                eventsTable.selectAll().count() shouldBe 2
            }
        }

        it("can route to an aggregate with a command that is both create and update") {
            val participantId = UUID.randomUUID()
            gateway.dispatch(Invite(participantId, UUID.randomUUID(), UUID.randomUUID(), DateTime.now()), metadata) shouldBe Right(Created)
            gateway.dispatch(Uninvite(participantId, DateTime.now()), metadata) shouldBe Right(Updated)
            gateway.dispatch(Invite(participantId, UUID.randomUUID(), UUID.randomUUID(), DateTime.now()), metadata) shouldBe Right(Updated)

            transaction(db) {
                eventsTable.selectAll().count() shouldBe 3
            }
        }

        it("can route to an aggregate created with function handles and a projection wired into it") {
            val surveyId = UUID.randomUUID()
            gateway.dispatch(CreateSurvey(surveyId, UUID.randomUUID(), mapOf(Locale.en to "name"), UUID.randomUUID(), DateTime.now()), metadata) shouldBe Right(Created)
            gateway.dispatch(Delete(surveyId, DateTime.now()), metadata) shouldBe Right(Updated)

            transaction(db) {
                eventsTable.selectAll().count() shouldBe 2
            }
        }

        it("can route to an aggregate created with function handles which is stateless for updates") {
            val surveySagaId = UUID.randomUUID()
            gateway.dispatch(Create(surveySagaId, UUID.randomUUID(), UUID.randomUUID(), mapOf(Locale.en to "name"), UUID.randomUUID(), DateTime.now()), metadata) shouldBe Right(Created)
            gateway.dispatch(StartCreatingSurvey(surveySagaId, DateTime.now()), metadata) shouldBe Right(Updated)

            transaction(db) {
                eventsTable.selectAll().count() shouldBe 2
            }
        }

        it("can route to an aggregate with which can fail commands based on reasonably complicated internal logic") {
            val surveyCaptureLayoutId = UUID.randomUUID()
            gateway.dispatch(Generate(surveyCaptureLayoutId, UUID.randomUUID(), DateTime.now()), metadata) shouldBe Right(Created)
            val sectionId = UUID.randomUUID()
            gateway.dispatch(RemoveSection(surveyCaptureLayoutId, sectionId, DateTime.now()), metadata) shouldBe Left(SectionNotFound)
            gateway.dispatch(AddSection(surveyCaptureLayoutId, sectionId, listOf(LocalizedText("text", Locale.en)), emptyList(), emptyList(), IntendedPurpose.standard, "code", null, DateTime.now()), metadata) shouldBe Right(Updated)
            gateway.dispatch(PositionQuestion(surveyCaptureLayoutId, UUID.randomUUID(), null, sectionId, DateTime.now()), metadata) shouldBe Right(Updated)
            gateway.dispatch(RemoveSection(surveyCaptureLayoutId, sectionId, DateTime.now()), metadata) shouldBe Right(Updated)

            transaction(db) {
                eventsTable.selectAll().count() shouldBe 4
            }
        }

        it("fails with a useful message when a command of a different type is used on an existing aggregate") {
            val surveyId = UUID.randomUUID()
            gateway.dispatch(CreateSurvey(surveyId, UUID.randomUUID(), mapOf(Locale.en to "name"), UUID.randomUUID(), DateTime.now()), metadata) shouldBe Right(Created)
            gateway.dispatch(Invite(surveyId, UUID.randomUUID(), UUID.randomUUID(), DateTime.now()), metadata) shouldBe
                Left(ConstructorTypeMismatch("ParticipantAggregate", SurveyCreated::class))
        }
    }
})
