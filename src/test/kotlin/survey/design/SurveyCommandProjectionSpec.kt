package survey.design

import io.kotlintest.Description
import io.kotlintest.shouldBe
import io.kotlintest.shouldThrow
import io.kotlintest.specs.ShouldSpec
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.joda.time.DateTime
import java.util.*

class SurveyCommandProjectionSpec : ShouldSpec() {
    private val projectionDatabase = Database.connect("jdbc:h2:mem:test;DB_CLOSE_DELAY=-1;", driver = "org.h2.Driver")

    override fun beforeTest(description: Description) {
        transaction(projectionDatabase) {
            addLogger(StdOutSqlLogger)
            SchemaUtils.createMissingTablesAndColumns(Surveys)
            Surveys.deleteAll()
        }
    }

    init {
        val (surveyQuery, surveyCommandProjector) = SurveyCommandProjection.create(projectionDatabase)

        val name = mapOf(Locale.en to "name")
        val accountId = UUID.randomUUID()
        val surveyId = UUID.randomUUID()
        val surveyCaptureLayoutId = UUID.randomUUID()

        "Created" {
            should("creates a Survey record") {
                surveyCommandProjector.first(Created(name, accountId, DateTime()), surveyId)
                surveyQuery.bySurvey(surveyId) shouldBe Survey(accountId, surveyId)
            }
        }

        "Generated" {
            "when the Survey exists" {
                should("add the survey capture layout id to the existing survey record") {
                    surveyCommandProjector.first(Created(name, accountId, DateTime()), surveyId)
                    surveyCommandProjector.second(Generated(surveyId, DateTime()), surveyCaptureLayoutId)
                    surveyQuery.bySurvey(surveyId) shouldBe Survey(accountId, surveyId, surveyCaptureLayoutId)
                }
            }

            "when the survey does not exist"{
                should("throw an exception") {
                    val exception = shouldThrow<RuntimeException> {
                        surveyCommandProjector.second(Generated(surveyId, DateTime()), surveyCaptureLayoutId)
                    }
                    exception.message shouldBe "Survey not found"
                }
            }
        }
    }
}