package survey.design

import eventsourcing.Left
import eventsourcing.Right
import io.kotlintest.shouldBe
import io.kotlintest.specs.ShouldSpec
import org.jetbrains.exposed.sql.Database
import java.util.*

class SurveyAggregateSpec : ShouldSpec({
    val namedAt = Date()
    val createdAt = Date()
    val deletedAt = Date()
    val restoredAt = Date()
    val name = mapOf(Locale.en to "name")
    val accountId = UUID.randomUUID()
    val aggregateId = UUID.randomUUID()
    val surveyCaptureLayoutAggregateId = UUID.randomUUID()

    "Created" {
        should("created a new Survey") {
            SurveyAggregate(Created(name, accountId, createdAt))
                .shouldBe(SurveyAggregate(name, accountId, deleted = false))
        }
    }

    "Renamed" {
        should("updated the name for one Locale") {
            SurveyAggregate(Created(name, accountId, createdAt))
                .updated(Renamed("rename", Locale.en, Date()))
                .name.getValue(Locale.en).shouldBe("rename")
        }
    }

    "Deleted" {
        should("set the deleted flag from true") {
            SurveyAggregate(Created(name, accountId, createdAt))
                .updated(Deleted(Date()))
                .deleted.shouldBe(true)
        }
    }

    "Restored" {
        should("set the deleted flag from true") {
            SurveyAggregate(Created(name, accountId, createdAt))
                .updated(Deleted(Date()))
                .updated(Restored(Date())).deleted
                .shouldBe(false)
        }
    }

    "CreateSurvey" {
        should("return Created event") {
            SurveyAggregate
                .create(NameTaken(false), CreateSurvey(aggregateId, surveyCaptureLayoutAggregateId, name, accountId, createdAt))
                .shouldBe(Right(Created(name, accountId, createdAt)))
        }

        "when Survey name already taken" {
            should("fail with SurveyNameNotUnique") {
                SurveyAggregate
                    .create(NameTaken(true), CreateSurvey(aggregateId, surveyCaptureLayoutAggregateId, name, accountId, createdAt))
                    .shouldBe(Left(SurveyNameNotUnique))
            }
        }
    }

    "Rename" {
        should("return Renamed event") {
            SurveyAggregate(Created(name, accountId, createdAt))
                .update(NameTaken(false), Rename(aggregateId, "rename", Locale.en, namedAt))
                .shouldBe(Right.list(Renamed("rename", Locale.en, namedAt)))
        }

        "when the name is taken by another aggregate" {
            should("fail with SurveyNameNotUnique") {
                SurveyAggregate(Created(name, accountId, createdAt))
                    .update(NameTaken(true), Rename(aggregateId, "rename", Locale.en, namedAt))
                    .shouldBe(Left(SurveyNameNotUnique))
            }
        }

        "when the new name is the same as the old name for the same locale" {
            SurveyAggregate(Created(name, accountId, createdAt))
                .update(NameTaken(false), Rename(aggregateId, name.getValue(Locale.en), Locale.en, namedAt))
                .shouldBe(Left(AlreadyRenamed))
        }

        "when the name is the same but for a different locale" {
            should("return Renamed event") {
                SurveyAggregate(Created(name, accountId, createdAt))
                    .update(NameTaken(false), Rename(aggregateId, name.getValue(Locale.en), Locale.de, namedAt))
                    .shouldBe(Right.list(Renamed(name.getValue(Locale.en), Locale.de, namedAt)))
            }
        }
    }

    "Delete" {
        should("return Deleted event") {
            SurveyAggregate(Created(name, accountId, createdAt))
                .update(Stub(), Delete(aggregateId, deletedAt))
                .shouldBe(Right.list(Deleted(deletedAt)))
        }

        "when Survey already deleted" {
            should("fail with AlreadyDeleted") {
                SurveyAggregate(Created(name, accountId, createdAt))
                    .updated(Deleted(deletedAt))
                    .update(Stub(), Delete(aggregateId, deletedAt))
                    .shouldBe(Left(AlreadyDeleted))
            }
        }
    }

    "Restore" {
        should("fail with NotDeleted") {
            SurveyAggregate(Created(name, accountId, createdAt))
                .update(Stub(), Restore(aggregateId, restoredAt))
                .shouldBe(Left(NotDeleted))
        }

        "when Survey already deleted" {
            should("return Restored event") {
                SurveyAggregate(Created(name, accountId, createdAt))
                    .updated(Deleted(deletedAt))
                    .update(Stub(), Restore(aggregateId, restoredAt))
                    .shouldBe(Right.list(Restored(restoredAt)))
            }
        }
    }
})

class Stub : SurveyNamesCommandQuery(inMemoryDatabase) {
    override fun nameExistsFor(accountId: UUID, name: String, locale: Locale): Boolean {
        throw IllegalStateException("Should not have been called")
    }
}

class NameTaken(val taken: Boolean) : SurveyNamesCommandQuery(inMemoryDatabase) {
    override fun nameExistsFor(accountId: UUID, name: String, locale: Locale) = taken
}

val inMemoryDatabase = Database.connect("jdbc:h2:mem:test", driver = "org.h2.Driver")
