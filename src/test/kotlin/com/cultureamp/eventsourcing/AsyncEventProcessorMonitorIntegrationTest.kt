package com.cultureamp.eventsourcing

import com.cultureamp.eventsourcing.example.CreateSurvey
import com.cultureamp.eventsourcing.example.Created
import com.cultureamp.eventsourcing.example.Invite
import com.cultureamp.eventsourcing.example.ParticipantAggregate
import com.cultureamp.eventsourcing.example.SurveyAggregate
import com.cultureamp.eventsourcing.example.SurveyNameAlwaysAvailable
import com.cultureamp.eventsourcing.example.SurveyNamesCommandProjector
import com.cultureamp.eventsourcing.sample.StandardEventMetadata
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.StdOutSqlLogger
import org.jetbrains.exposed.sql.addLogger
import org.jetbrains.exposed.sql.transactions.transaction
import org.joda.time.DateTime
import java.util.UUID

class AsyncEventProcessorMonitorIntegrationTest : DescribeSpec({
    val db = PgTestConfig.db ?: Database.connect(url = "jdbc:h2:mem:test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;", driver = "org.h2.Driver")
    val tableEvent = Events()
    val tableH2 = H2DatabaseEventStore.eventsTable()
    val table = if (PgTestConfig.db != null) tableEvent else tableH2
    val eventsSequenceStatsTable = EventsSequenceStats()
    val eventStore = RelationalDatabaseEventStore.create<StandardEventMetadata>(db)
    val bookmarksTable = Bookmarks()
    val bookmarkStore = RelationalDatabaseBookmarkStore(db, bookmarksTable)
    val accountId = UUID.randomUUID()
    val commandGateway = CommandGateway(
        eventStore,
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
    )

    beforeTest {
        transaction(db) {
            addLogger(StdOutSqlLogger)
            SchemaUtils.create(table)
            SchemaUtils.create(eventsSequenceStatsTable)
            SchemaUtils.create(bookmarksTable)
        }
    }

    afterTest {
        transaction(db) {
            SchemaUtils.drop(bookmarksTable)
            SchemaUtils.drop(eventsSequenceStatsTable)
            SchemaUtils.drop(table)
        }
    }

    describe("run") {
        it("calculates lag taking into account eventType") {
            val projector = SurveyNamesCommandProjector(db)
            val bookmarkName = BookmarkName("SurveyNames")
            val eventProcessor = EventProcessor.from(projector)
            val asyncEventProcessor = BatchedAsyncEventProcessor(eventStore, bookmarkStore, bookmarkName, eventProcessor)

            var capturedLag: Lag? = null
            val metrics: (Lag) -> Unit = {
                capturedLag = it
            }
            val asyncEventProcessorMonitor = AsyncEventProcessorMonitor(
                listOf(asyncEventProcessor),
                metrics
            )

            val surveyId = UUID.randomUUID()
            commandGateway.dispatch(CreateSurvey(surveyId, UUID.randomUUID(), emptyMap(), UUID.randomUUID(), DateTime.now()), StandardEventMetadata(accountId))
            commandGateway.dispatch(Invite(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), DateTime.now()), StandardEventMetadata(accountId))

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
