package com.cultureamp.eventsourcing

import com.cultureamp.eventsourcing.example.AddSection
import com.cultureamp.eventsourcing.example.AlwaysBoppable
import com.cultureamp.eventsourcing.example.Boop
import com.cultureamp.eventsourcing.example.Bop
import com.cultureamp.eventsourcing.example.Create
import com.cultureamp.eventsourcing.example.CreateSimpleThing
import com.cultureamp.eventsourcing.example.CreateSurvey
import com.cultureamp.eventsourcing.example.CreateThing
import com.cultureamp.eventsourcing.example.Delete
import com.cultureamp.eventsourcing.example.DemographicSectionsAlreadyPositioned
import com.cultureamp.eventsourcing.example.DescriptionsAlreadyChanged
import com.cultureamp.eventsourcing.example.Generate
import com.cultureamp.eventsourcing.example.IntendedPurpose
import com.cultureamp.eventsourcing.example.InvalidOrderForSections
import com.cultureamp.eventsourcing.example.InvalidSectionId
import com.cultureamp.eventsourcing.example.Invite
import com.cultureamp.eventsourcing.example.Locale
import com.cultureamp.eventsourcing.example.LocalizedText
import com.cultureamp.eventsourcing.example.LongDescriptionAlreadyChanged
import com.cultureamp.eventsourcing.example.ParticipantAggregate
import com.cultureamp.eventsourcing.example.PositionQuestion
import com.cultureamp.eventsourcing.example.PositionedAfterQuestionInWrongSection
import com.cultureamp.eventsourcing.example.QuestionAlreadyInPosition
import com.cultureamp.eventsourcing.example.QuestionAlreadyRemovedFromSection
import com.cultureamp.eventsourcing.example.QuestionNotFound
import com.cultureamp.eventsourcing.example.RemoveSection
import com.cultureamp.eventsourcing.example.RenameAlreadyActioned
import com.cultureamp.eventsourcing.example.SectionAlreadyAdded
import com.cultureamp.eventsourcing.example.SectionAlreadyMoved
import com.cultureamp.eventsourcing.example.SectionAlreadyRemoved
import com.cultureamp.eventsourcing.example.SectionAlreadyRestored
import com.cultureamp.eventsourcing.example.SectionCodeNotUnique
import com.cultureamp.eventsourcing.example.SectionDescriptionsAlreadyRemoved
import com.cultureamp.eventsourcing.example.SectionError
import com.cultureamp.eventsourcing.example.SectionHasDifferentIntendedPurpose
import com.cultureamp.eventsourcing.example.SectionNotFound
import com.cultureamp.eventsourcing.example.ShortDescriptionAlreadyChanged
import com.cultureamp.eventsourcing.example.SimpleThingAggregate
import com.cultureamp.eventsourcing.example.StartCreatingSurvey
import com.cultureamp.eventsourcing.example.SurveyAggregate
import com.cultureamp.eventsourcing.example.SurveyCaptureLayoutAggregate
import com.cultureamp.eventsourcing.example.SurveyNameAlwaysAvailable
import com.cultureamp.eventsourcing.example.SurveySagaAggregate
import com.cultureamp.eventsourcing.example.ThingAggregate
import com.cultureamp.eventsourcing.example.Tweak
import com.cultureamp.eventsourcing.example.Twerk
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

class CommandGatewayIntegrationTest : DescribeSpec({
    val h2DbUrl = "jdbc:h2:mem:test;MODE=MySQL;DB_CLOSE_DELAY=-1;"
    val h2Driver = "org.h2.Driver"
    val db = Database.connect(url = h2DbUrl, driver = h2Driver)
    val eventsTable = H2DatabaseEventStore.eventsTable()
    val eventStore = RelationalDatabaseEventStore.create<StandardEventMetadata>(db)
    val routes = listOf(
        Route.from(
            PizzaAggregate.Companion::create,
            PizzaAggregate::update,
            ::PizzaAggregate,
            PizzaAggregate::updated,
            PizzaAggregate::aggregateType

        ),
        Route.from(ThingAggregate.partial(AlwaysBoppable)),
        Route.from(SimpleThingAggregate),
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
    val gateway = CommandGateway(eventStore, routes)

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
            result shouldBe Success(Created)
            transaction(db) {
                eventsTable.selectAll().count() shouldBe 1
            }
        }

        it("fails on creation with duplicate UUIDs") {
            val aggregateId = UUID.randomUUID()
            val result = gateway.dispatch(CreateClassicPizza(aggregateId, PizzaStyle.MARGHERITA), metadata)
            result shouldBe Success(Created)
            val result2 = gateway.dispatch(CreateClassicPizza(aggregateId, PizzaStyle.HAWAIIAN), metadata)
            result2 shouldBe Failure(Left(AggregateAlreadyExists))
            transaction(db) {
                eventsTable.selectAll().count() shouldBe 1
            }
        }

        it("accepts a creation then update event") {
            val aggregateId = UUID.randomUUID()
            val result = gateway.dispatch(CreateClassicPizza(aggregateId, PizzaStyle.MARGHERITA), metadata)
            result shouldBe Success(Created)
            val result2 = gateway.dispatch(AddTopping(aggregateId, PizzaTopping.PINEAPPLE), StandardEventMetadata("alice"))
            result2 shouldBe Success(Updated)
            transaction(db) {
                eventsTable.selectAll().count() shouldBe 2
            }
        }

        it("rejects command when invalid event sequence is provided") {
            val aggregateId = UUID.randomUUID()
            val result = gateway.dispatch(CreateClassicPizza(aggregateId, PizzaStyle.MARGHERITA), metadata)
            result shouldBe Success(Created)
            val result2 = gateway.dispatch(EatPizza(aggregateId), StandardEventMetadata("alice"))
            result2 shouldBe Success(Updated)
            val result3 = gateway.dispatch(AddTopping(aggregateId, PizzaTopping.PINEAPPLE), metadata)
            result3.shouldBeInstanceOf<Failure<PizzaAlreadyEaten>>()
            transaction(db) {
                eventsTable.selectAll().count() shouldBe 2
            }
        }

        it("fails on updating with unknown UUID") {
            val aggregateId = UUID.randomUUID()
            val result1 = gateway.dispatch(AddTopping(aggregateId, PizzaTopping.PINEAPPLE), metadata)
            result1 shouldBe Failure(Left(AggregateNotFound))
            transaction(db) {
                eventsTable.selectAll().count() shouldBe 0
            }
        }

        it("can route to an aggregate created with function handles") {
            val pizzaId = UUID.randomUUID()
            gateway.dispatch(CreateClassicPizza(pizzaId, PizzaStyle.MARGHERITA), metadata) shouldBe Success(Created)
            gateway.dispatch(AddTopping(pizzaId, PizzaTopping.PINEAPPLE), metadata) shouldBe Success(Updated)

            transaction(db) {
                eventsTable.selectAll().count() shouldBe 2
            }
        }

        it("can route to a simple aggregate") {
            val simpleThingId = UUID.randomUUID()
            gateway.dispatch(CreateSimpleThing(simpleThingId), metadata) shouldBe Success(Created)
            gateway.dispatch(Boop(simpleThingId), metadata) shouldBe Success(Updated)
            gateway.dispatch(Twerk(simpleThingId, "dink"), metadata) shouldBe Success(Updated)

            transaction(db) {
                eventsTable.selectAll().count() shouldBe 3
            }
        }

        it("can route to an aggregate with a projection wired into it") {
            val thingId = UUID.randomUUID()
            gateway.dispatch(CreateThing(thingId), metadata) shouldBe Success(Created)
            gateway.dispatch(Bop(thingId), metadata) shouldBe Success(Updated)
            gateway.dispatch(Tweak(thingId, "donk"), metadata) shouldBe Success(Updated)

            transaction(db) {
                eventsTable.selectAll().count() shouldBe 3
            }
        }

        it("can route to a stateless aggregate") {
            val paymentSagaId = UUID.randomUUID()
            gateway.dispatch(StartPaymentSaga(paymentSagaId, UUID.randomUUID(), "bank details", 42), metadata) shouldBe Success(Created)
            gateway.dispatch(StartThirdPartyPayment(paymentSagaId, DateTime.now()), metadata) shouldBe Success(Updated)

            transaction(db) {
                eventsTable.selectAll().count() shouldBe 2
            }
        }

        it("can route to an aggregate with a command that is both create and update") {
            val participantId = UUID.randomUUID()
            gateway.dispatch(Invite(participantId, UUID.randomUUID(), UUID.randomUUID(), DateTime.now()), metadata) shouldBe Success(Created)
            gateway.dispatch(Uninvite(participantId, DateTime.now()), metadata) shouldBe Success(Updated)
            gateway.dispatch(Invite(participantId, UUID.randomUUID(), UUID.randomUUID(), DateTime.now()), metadata) shouldBe Success(Updated)

            transaction(db) {
                eventsTable.selectAll().count() shouldBe 3
            }
        }

        it("can route to an aggregate created with function handles and a projection wired into it") {
            val surveyId = UUID.randomUUID()
            gateway.dispatch(CreateSurvey(surveyId, UUID.randomUUID(), mapOf(Locale.en to "name"), UUID.randomUUID(), DateTime.now()), metadata) shouldBe Success(Created)
            gateway.dispatch(Delete(surveyId, DateTime.now()), metadata) shouldBe Success(Updated)

            transaction(db) {
                eventsTable.selectAll().count() shouldBe 2
            }
        }

        it("can route to an aggregate created with function handles which is stateless for updates") {
            val surveySagaId = UUID.randomUUID()
            gateway.dispatch(Create(surveySagaId, UUID.randomUUID(), UUID.randomUUID(), mapOf(Locale.en to "name"), UUID.randomUUID(), DateTime.now()), metadata) shouldBe Success(Created)
            gateway.dispatch(StartCreatingSurvey(surveySagaId, DateTime.now()), metadata) shouldBe Success(Updated)

            transaction(db) {
                eventsTable.selectAll().count() shouldBe 2
            }
        }

        it("can route to an aggregate with which can fail commands based on reasonably complicated internal logic") {
            val surveyCaptureLayoutId = UUID.randomUUID()
            gateway.dispatch(Generate(surveyCaptureLayoutId, UUID.randomUUID(), DateTime.now()), metadata) shouldBe Success(Created)
            val sectionId = UUID.randomUUID()
            gateway.dispatch(RemoveSection(surveyCaptureLayoutId, sectionId, DateTime.now()), metadata) shouldBe Failure(Right(SectionNotFound))
            gateway.dispatch(AddSection(surveyCaptureLayoutId, sectionId, listOf(LocalizedText("text", Locale.en)), emptyList(), emptyList(), IntendedPurpose.standard, "code", null, DateTime.now()), metadata) shouldBe Success(Updated)
            gateway.dispatch(PositionQuestion(surveyCaptureLayoutId, UUID.randomUUID(), null, sectionId, DateTime.now()), metadata) shouldBe Success(Updated)
            gateway.dispatch(RemoveSection(surveyCaptureLayoutId, sectionId, DateTime.now()), metadata) shouldBe Success(Updated)

            transaction(db) {
                eventsTable.selectAll().count() shouldBe 4
            }
        }

        it("allows exhaustive matching on errors") {
            val surveyCaptureLayoutId = UUID.randomUUID()
            gateway.dispatch(Generate(surveyCaptureLayoutId, UUID.randomUUID(), DateTime.now()), metadata) shouldBe Success(Created)
            val sectionId = UUID.randomUUID()
            val result = gateway.dispatch(RemoveSection(surveyCaptureLayoutId, sectionId, DateTime.now()), metadata)// shouldBe Failure(Right(SectionNotFound))
            val foo = when (result) {
                is Failure -> when (val error = result.error) {
                    is Left -> when (error.value) {
                        NoConstructorForCommand -> 501 // not implemented
                        AggregateAlreadyExists -> 409 // conflict
                        AggregateNotFound -> 404 // not found
                        ConcurrencyError -> 409 // conflict
                    }
                    is Right -> when (error.value) {
                        is SectionError -> TODO()
                        else -> TODO()
                    }
                }
                is Success -> when (result.value) {
                    Created -> 201
                    Updated -> 200
                }
            }
        }
    }
})


