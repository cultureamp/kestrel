import com.cultureamp.eventsourcing.*
import com.cultureamp.eventsourcing.example.*
import com.cultureamp.eventsourcing.sample.StandardEventMetadata
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import org.joda.time.DateTime
import java.util.*
import kotlin.reflect.KClass


class EventConsumerStatisticsTest : DescribeSpec({
    val db = Database.connect(url = "jdbc:h2:mem:test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;", driver = "org.h2.Driver")
    val eventStore = object : EventStore<EventMetadata> {

        var events = mutableListOf<Pair<Event<EventMetadata>, UUID>>()
        var seq = 0L
        override fun sink(newEvents: List<Event<EventMetadata>>, aggregateId: UUID): Either<CommandError, Long> {
            events += newEvents.map { it to aggregateId }
            return Right(events.size.toLong())
        }

        override fun getAfter(
            sequence: Long,
            eventClasses: List<KClass<out DomainEvent>>,
            batchSize: Int
        ): List<SequencedEvent<out EventMetadata>> {
            val result = events
            events = mutableListOf()
            return result.map { SequencedEvent(it.first, seq++) }
        }

        override fun lastSequence(eventClasses: List<KClass<out DomainEvent>>): Long {
            return seq + events.size
        }

        override fun eventsFor(aggregateId: UUID): List<Event<EventMetadata>> {
            val results = events.filter { it.second == aggregateId }.map { it.first }
            events.removeAll { it.second == aggregateId }
            return results
        }

    }
    val table = Bookmarks()
    transaction {
        SchemaUtils.createMissingTablesAndColumns(table)
    }
    val bookmarkStore = CachingBookmarkStore(RelationalDatabaseBookmarkStore(db, table))
    val accountId = UUID.randomUUID()

    describe("Confirming statistics collectors are called") {
        val metadata = StandardEventMetadata(accountId)

        it("Statistics collector is called with correct params") {
            val projector = ParticipantProjector(db)
            val bookmarkName = "ParticipantBookmark"
            val eventProcessor = EventProcessor.from(projector)
            val statsCollector = object : StatisticsCollector {
                val records = mutableListOf<Triple<AsyncEventProcessor<*>, SequencedEvent<*>, Long>>()
                override fun eventProcessed(
                    processor: AsyncEventProcessor<*>,
                    event: SequencedEvent<*>,
                    durationMs: Long
                ) {
                    records += Triple(processor, event,  durationMs)
                }

            }
            val asyncEventProcessor = BatchedAsyncEventProcessor(eventStore, bookmarkStore, bookmarkName, eventProcessor, stats = statsCollector)

            val aggregateId = UUID.randomUUID()
            val surveyPeriodId = UUID.randomUUID()
            val participantId = UUID.randomUUID()
            transaction(db) {
                eventStore.sink(listOf(Event(UUID.randomUUID(), aggregateId, 1, "Invited", DateTime.now(), metadata, Invited(surveyPeriodId, participantId, DateTime.now()))), aggregateId)

                println("About to process a batch")
                asyncEventProcessor.processOneBatch()

                statsCollector.records.size shouldBe 1
                statsCollector.records.any { it.first == asyncEventProcessor && it.second.event.aggregateId == aggregateId } shouldBe true
                statsCollector.records.first { it.first == asyncEventProcessor && it.second.event.aggregateId == aggregateId }.third shouldBeLessThan 1000
            }
        }
    }
})
