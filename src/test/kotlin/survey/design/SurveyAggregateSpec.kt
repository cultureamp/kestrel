package survey.design

import eventsourcing.Left
import eventsourcing.Right
import io.kotlintest.Description
import io.kotlintest.shouldBe
import io.kotlintest.specs.ShouldSpec
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.*
import java.util.Date

class SurveyAggregateSpec : ShouldSpec() {
    private val projectionDatabase = Database.connect("jdbc:h2:mem:test;DB_CLOSE_DELAY=-1;", driver = "org.h2.Driver")

    override fun beforeTest(description: Description) {
        transaction(projectionDatabase) {
            addLogger(StdOutSqlLogger)
            SchemaUtils.createMissingTablesAndColumns(SurveyNames)
            SurveyNames.deleteAll()
        }
    }

    init {
        val surveyNamesCommandQuery = SurveyNamesCommandQuery(projectionDatabase)
        val surveyNamesCommandProjector = SurveyNamesCommandProjector(projectionDatabase)

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
                    .create(
                        surveyNamesCommandQuery,
                        CreateSurvey(aggregateId, surveyCaptureLayoutAggregateId, name, accountId, createdAt)
                    )
                    .shouldBe(Right(Created(name, accountId, createdAt)))
            }

            "when Survey name already taken" {
                should("fail with SurveyNameNotUnique") {
                    surveyNamesCommandProjector.project(Created(name, UUID.randomUUID(), Date()), UUID.randomUUID())
                    SurveyAggregate
                        .create(
                            surveyNamesCommandQuery,
                            CreateSurvey(aggregateId, surveyCaptureLayoutAggregateId, name, accountId, createdAt)
                        )
                        .shouldBe(Left(SurveyNameNotUnique))
                }
            }
        }

        "Rename" {
            should("return Renamed event") {
                SurveyAggregate(Created(name, accountId, createdAt))
                    .update(surveyNamesCommandQuery, Rename(aggregateId, "rename", Locale.en, namedAt))
                    .shouldBe(Right.list(Renamed("rename", Locale.en, namedAt)))
            }

            "when the name is the same as the existing name" {
                should("fail with AlreadyRenamed") {
                    SurveyAggregate(Created(name, accountId, createdAt))
                        .update(surveyNamesCommandQuery, Rename(aggregateId, name.getValue(Locale.en), Locale.en, namedAt))
                        .shouldBe(Left(AlreadyRenamed))
                }
            }

            "when the name is taken by another aggregate" {
                should("fail with SurveyNameNotUnique") {
                    surveyNamesCommandProjector.project(Created(mapOf(Locale.en to "rename"), UUID.randomUUID(), Date()), UUID.randomUUID())
                    SurveyAggregate(Created(name, accountId, createdAt))
                        .update(surveyNamesCommandQuery, Rename(aggregateId, "rename", Locale.en, namedAt))
                        .shouldBe(Left(SurveyNameNotUnique))
                }
            }



            "when the name is the same but for a different locale" {
                should("return Renamed event") {
                    surveyNamesCommandProjector.project(Created(name, UUID.randomUUID(), Date()), UUID.randomUUID())
                    SurveyAggregate(Created(name, accountId, createdAt))
                        .update(
                            surveyNamesCommandQuery,
                            Rename(aggregateId, name.getValue(Locale.en), Locale.de, namedAt)
                        )
                        .shouldBe(Right.list(Renamed(name.getValue(Locale.en), Locale.de, namedAt)))
                }
            }
        }

        "Delete" {
            should("return Deleted event") {
                SurveyAggregate(Created(name, accountId, createdAt))
                    .update(surveyNamesCommandQuery, Delete(aggregateId, deletedAt))
                    .shouldBe(Right.list(Deleted(deletedAt)))
            }

            "when Survey already deleted" {
                should("fail with AlreadyDeleted") {
                    SurveyAggregate(Created(name, accountId, createdAt))
                        .updated(Deleted(deletedAt))
                        .update(surveyNamesCommandQuery, Delete(aggregateId, deletedAt))
                        .shouldBe(Left(AlreadyDeleted))
                }
            }
        }

        "Restore" {
            should("fail with NotDeleted") {
                SurveyAggregate(Created(name, accountId, createdAt))
                    .update(surveyNamesCommandQuery, Restore(aggregateId, restoredAt))
                    .shouldBe(Left(NotDeleted))
            }

            "when Survey already deleted" {
                should("return Restored event") {
                    SurveyAggregate(Created(name, accountId, createdAt))
                        .updated(Deleted(deletedAt))
                        .update(surveyNamesCommandQuery, Restore(aggregateId, restoredAt))
                        .shouldBe(Right.list(Restored(restoredAt)))
                }
            }
        }
    }
}


