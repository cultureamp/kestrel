import com.cultureamp.eventsourcing.*
import com.cultureamp.eventsourcing.example.*
import com.cultureamp.eventsourcing.sample.StandardEventMetadata
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.StdOutSqlLogger
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.addLogger
import org.jetbrains.exposed.sql.transactions.transaction
import org.joda.time.DateTime
import java.lang.RuntimeException
import java.util.*

class UpcastTest : DescribeSpec({
    val db = Database.connect(url = "jdbc:h2:mem:test;MODE=MySQL;DB_CLOSE_DELAY=-1;", driver = "org.h2.Driver")
    val eventsSequenceStats = RelationalDatabaseEventsSequenceStats(db)
    val eventStore = RelationalDatabaseEventStore.create<StandardEventMetadata>(db, eventsSequenceStats = eventsSequenceStats)
    val bookmarkStore = RelationalDatabaseBookmarkStore(db)
    val accountId = UUID.randomUUID()
    val commandGateway = CommandGateway(
        eventStore,
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
            SchemaUtils.create(eventsSequenceStats.table)
            SchemaUtils.create(bookmarkStore.table)
            SchemaUtils.create(eventStore.events)
        }
    }

    afterTest {
        transaction(db) {
            SchemaUtils.drop(eventStore.events)
            SchemaUtils.drop(bookmarkStore.table)
            SchemaUtils.drop(eventsSequenceStats.table)
        }
    }

    describe("Upcasting the old events to the new events") {
        val metadata = StandardEventMetadata(accountId)
        val timestamp = DateTime.now()
        it("Rereinvited -> Reinvited") {
            val reinvitedAt = timestamp
            val reinvited = Reinvited(reinvitedAt)
            val reinvitedEvent = Rereinvited(reinvitedAt)
            reinvitedEvent::class.annotations.filterIsInstance<UpcastEvent>().forEach {
                it.upcasting(reinvitedEvent, metadata) shouldBe reinvited

            }
        }

        it("BatchedAsyncEventProcessor upcasting can be turned off") {
            val projector = ParticipantProjector(db)
            val bookmarkName = "ParticipantBookmark"
            val eventProcessor = EventProcessor.from(projector)
            val asyncEventProcessor = BatchedAsyncEventProcessor(eventStore, eventsSequenceStats, bookmarkStore, bookmarkName, eventProcessor, upcasting = false)

            transaction(db) {
                val participantId = UUID.randomUUID()
                commandGateway.dispatch(
                    Invite(participantId, UUID.randomUUID(), UUID.randomUUID(), timestamp),
                    metadata
                )
                commandGateway.dispatch(Uninvite(participantId, timestamp), metadata)
                commandGateway.dispatch(Rereinvite(participantId, timestamp), metadata)

                val events = eventStore.eventsFor(participantId)
                events.size shouldBe 3

                val exception = shouldThrow<RuntimeException> {
                    asyncEventProcessor.processOneBatch()
                }
                exception.message shouldBe "Projector only supports upcasted events"
            }
        }

        it("BatchedAsyncEventProcessor is upcasting") {
            val projector = ParticipantProjector(db)
            val bookmarkName = "ParticipantBookmark"
            val eventProcessor = EventProcessor.from(projector)
            val asyncEventProcessor = BatchedAsyncEventProcessor(eventStore, eventsSequenceStats, bookmarkStore, bookmarkName, eventProcessor)

            transaction(db) {
                val participantId = UUID.randomUUID()
                commandGateway.dispatch(
                    Invite(participantId, UUID.randomUUID(), UUID.randomUUID(), timestamp),
                    metadata
                )
                commandGateway.dispatch(Uninvite(participantId, timestamp), metadata)
                commandGateway.dispatch(Rereinvite(participantId, timestamp), metadata)

                val events = eventStore.eventsFor(participantId)
                events.size shouldBe 3
                asyncEventProcessor.processOneBatch()
                projector.isInvited(participantId) shouldBe true
            }
        }
    }
})
