package com.cultureamp.eventsourcing

import com.cultureamp.eventsourcing.example.CreateSurvey
import com.cultureamp.eventsourcing.example.Created
import com.cultureamp.eventsourcing.example.Invite
import com.cultureamp.eventsourcing.example.ParticipantAggregate
import com.cultureamp.eventsourcing.example.SurveyAggregate
import com.cultureamp.eventsourcing.example.SurveyNameAlwaysAvailable
import com.cultureamp.eventsourcing.example.SurveyNamesCommandProjector
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.StdOutSqlLogger
import org.jetbrains.exposed.sql.addLogger
import org.jetbrains.exposed.sql.transactions.transaction
import org.joda.time.DateTime
import java.util.*

class AsyncEventProcessorMonitorIntegrationTest : DescribeSpec({
    val h2DbUrl = "jdbc:h2:mem:test;MODE=MySQL;DB_CLOSE_DELAY=-1;"
    val h2Driver = "org.h2.Driver"
    val db = Database.connect(url = h2DbUrl, driver = h2Driver)
    val table = H2DatabaseEventStore.eventsTable()
    val eventStore =  RelationalDatabaseEventStore.create<EventMetadata>(db)
    val bookmarksTable = Bookmarks()
    val bookmarkStore = RelationalDatabaseBookmarkStore(db, bookmarksTable)
    val commandGateway = CommandGateway(eventStore, listOf(
        Route.from(
            SurveyAggregate.Companion::create.partial(SurveyNameAlwaysAvailable),
            SurveyAggregate::update.partial2(SurveyNameAlwaysAvailable),
            ::SurveyAggregate,
            SurveyAggregate::updated
        ),
        Route.from(
            ParticipantAggregate.Companion::create,
            ParticipantAggregate::update,
            ParticipantAggregate.Companion::created,
            ParticipantAggregate::updated
        )
    ))

    beforeTest {
        transaction(db) {
            addLogger(StdOutSqlLogger)
            SchemaUtils.create(table)
            SchemaUtils.create(bookmarksTable)
        }
    }

    afterTest {
        transaction(db) {
            SchemaUtils.drop(bookmarksTable)
            SchemaUtils.drop(table)
        }
    }

    describe("run") {
        it("calculates lag taking into account eventType") {
            val projector = SurveyNamesCommandProjector(db)
            val bookmarkName = "SurveyNames"
            val eventProcessor = EventProcessor.from(projector)
            val asyncEventProcessor = AsyncEventProcessor(eventStore, bookmarkStore, bookmarkName, eventProcessor)

            var capturedLag: Lag? = null
            val metrics: (Lag) -> Unit = {
                capturedLag = it
            }
            val asyncEventProcessorMonitor = AsyncEventProcessorMonitor(
                listOf(asyncEventProcessor),
                metrics
            )

            val surveyId = UUID.randomUUID()
            commandGateway.dispatch(CreateSurvey(surveyId, UUID.randomUUID(), emptyMap(), UUID.randomUUID(), DateTime.now()), EventMetadata())
            commandGateway.dispatch(Invite(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), DateTime.now()), EventMetadata())

            eventStore.lastSequence() shouldBe 2
            eventStore.lastSequence(listOf(Created::class)) shouldBe 1
            bookmarkStore.bookmarkFor(bookmarkName) shouldBe Bookmark(bookmarkName, 0)

            asyncEventProcessorMonitor.run()
            capturedLag?.lag shouldBe 1

            asyncEventProcessor.processOneBatch()
            bookmarkStore.bookmarkFor(bookmarkName) shouldBe Bookmark(bookmarkName, 1)
            asyncEventProcessorMonitor.run()
            capturedLag?.lag shouldBe 0
        }
    }
})
