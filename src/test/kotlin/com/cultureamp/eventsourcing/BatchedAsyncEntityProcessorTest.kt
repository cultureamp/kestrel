package com.cultureamp.eventsourcing

import com.cultureamp.common.Action
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.time.Duration
import java.time.LocalDateTime
import java.util.UUID

class BatchedAsyncEntityProcessorTest : DescribeSpec({
    val baseTime = LocalDateTime.of(2026, 8, 10, 9, 0, 0, 0)
    /**
     * A boundary pinned at [safeBefore] and read at [readAt]. The gap between them is how long the boundary has been
     * held back, which is what the processor pre-filters a stall check on, so a spec that wants a stall has to say the
     * boundary was read well after it was pinned.
     */
    fun boundaryOf(safeBefore: LocalDateTime, readAt: LocalDateTime = safeBefore) =
        SafeBoundary { SafeBoundaryReading(safeBefore, readAt) }

    val wellAfterAnyRow = boundaryOf(baseTime.plus(Duration.ofHours(1)))
    val accountId = UUID.randomUUID()

    fun goalRelationship(secondsAfterBase: Int, id: UUID = UUID.randomUUID()) =
        GoalRelationship(id, UUID.randomUUID(), UUID.randomUUID(), accountId, createdAt = fixtureCreatedAt, updatedAt = baseTime.plusSeconds(secondsAfterBase.toLong()))

    fun positioned(goalRelationship: GoalRelationship) = PositionedEntity(goalRelationship, goalRelationship.position)

    fun stallableProcessorFor(
        rows: List<GoalRelationship>,
        safeBoundary: SafeBoundary,
        bookmarkStore: EntityBookmarkStore = InMemoryEntityBookmarkStore(),
        stallBehaviour: StallBehaviour = StallBehaviour.Throw,
        batchSize: Int = 1000,
        processed: MutableList<GoalRelationship> = mutableListOf(),
    ) = BatchedAsyncEntityProcessor(
        entitySource = InMemoryEntitySource(rows.map(::positioned)),
        entityUpdatedAtStats = InMemoryEntitySource(rows.map(::positioned)),
        bookmarkStore = bookmarkStore,
        bookmarkName = "goal-relationships",
        entityProcessor = EntityProcessor.from { row: GoalRelationship -> processed += row },
        safeBoundary = safeBoundary,
        stallThreshold = Duration.ofHours(1),
        stallBehaviour = stallBehaviour,
        batchSize = batchSize,
        startLog = {},
        endLog = { _, _ -> },
    )

    fun processorFor(
        goalRelationships: List<GoalRelationship>,
        processed: MutableList<GoalRelationship>,
        bookmarkStore: EntityBookmarkStore,
        batchSize: Int = 1000,
        safeBoundary: SafeBoundary = wellAfterAnyRow,
    ) = BatchedAsyncEntityProcessor(
        entitySource = InMemoryEntitySource(goalRelationships.map(::positioned)),
        entityUpdatedAtStats = InMemoryEntitySource(goalRelationships.map(::positioned)),
        bookmarkStore = bookmarkStore,
        bookmarkName = "goal-relationships",
        entityProcessor = EntityProcessor.from { goalRelationship: GoalRelationship -> processed += goalRelationship },
        safeBoundary = safeBoundary,
        batchSize = batchSize,
        startLog = {},
        endLog = { _, _ -> },
    )

    describe("processOneBatch") {
        it("processes rows in updated-at order and bookmarks each one") {
            val first = goalRelationship(1)
            val second = goalRelationship(2)
            val third = goalRelationship(3)
            val processed = mutableListOf<GoalRelationship>()
            val bookmarkStore = InMemoryEntityBookmarkStore()

            // deliberately out of order in the source, which is expected to sort them
            val action = processorFor(listOf(third, first, second), processed, bookmarkStore).processOneBatch()

            processed shouldBe listOf(first, second, third)
            bookmarkStore.saved.map { it.position } shouldBe listOf(first.position, second.position, third.position)
            bookmarkStore.bookmarkFor("goal-relationships") shouldBe EntityBookmark("goal-relationships", third.position)
            action shouldBe Action.Wait
        }

        it("starts from an existing bookmark rather than the beginning") {
            val first = goalRelationship(1)
            val second = goalRelationship(2)
            val processed = mutableListOf<GoalRelationship>()
            val bookmarkStore = InMemoryEntityBookmarkStore()
            bookmarkStore.save("goal-relationships", first.position)

            processorFor(listOf(first, second), processed, bookmarkStore).processOneBatch()

            processed shouldBe listOf(second)
        }

        it("tiebreaks on id so that rows sharing an updated-at are each processed exactly once across batches") {
            val sameSecond = (1..5).map { goalRelationship(1) }
            val processed = mutableListOf<GoalRelationship>()
            val bookmarkStore = InMemoryEntityBookmarkStore()
            val processor = processorFor(sameSecond, processed, bookmarkStore, batchSize = 2)

            processor.processOneBatch() shouldBe Action.Continue
            processor.processOneBatch() shouldBe Action.Continue
            processor.processOneBatch() shouldBe Action.Wait
            processor.processOneBatch() shouldBe Action.Wait

            processed.toSet() shouldBe sameSecond.toSet()
            processed.size shouldBe 5
        }

        it("does not read rows at or beyond the safe boundary, and picks them up once the boundary passes them") {
            val old = goalRelationship(0)
            val fresh = goalRelationship(10)
            val processed = mutableListOf<GoalRelationship>()
            val bookmarkStore = InMemoryEntityBookmarkStore()
            var now = fresh.updatedAt.plus(Duration.ofMillis(500))

            val processor = processorFor(
                listOf(old, fresh),
                processed,
                bookmarkStore,
                safeBoundary = SafeBoundary.unsafeFixedDelay(Duration.ofMillis(1000)) { now },
            )

            processor.processOneBatch()
            processed shouldBe listOf(old)

            now = fresh.updatedAt.plus(Duration.ofMillis(1500))
            processor.processOneBatch()
            processed shouldBe listOf(old, fresh)
        }

        it("throws once the rows it cannot read span more than the stall threshold") {
            // the boundary pinned at baseTime, as an open transaction started then would pin it, with two hours of
            // writes stacked up above it
            val processor = stallableProcessorFor(
                listOf(goalRelationship(secondsAfterBase = 2 * 60 * 60)),
                safeBoundary = object : SafeBoundary {
                    override fun read() = SafeBoundaryReading(safeBefore = baseTime, readAt = baseTime.plusSeconds(3 * 60 * 60))
                    override fun describeBlockers() = "pid=999 application_name='psql' [UNRECOGNISED]"
                },
            )

            val exception = shouldThrow<SafeBoundaryStalledException> { processor.processOneBatch() }

            // the report has to name the session to go and close, or it just says "publishing is behind"
            exception.message!! shouldContain "application_name='psql'"
            exception.message!! shouldContain "goal-relationships"
        }

        it("does not query the head of the table while the boundary is too fresh for a stall to be possible") {
            // the pre-filter: a row cannot be stamped after the boundary was read, so a boundary read moments after it
            // was pinned cannot have a threshold's worth of writes above it, whatever the table contains
            var headReads = 0
            val waiting = goalRelationship(secondsAfterBase = 2 * 60 * 60)
            val processor = BatchedAsyncEntityProcessor(
                entitySource = InMemoryEntitySource(listOf(positioned(waiting))),
                entityUpdatedAtStats = EntityUpdatedAtStats.from { headReads++; waiting.updatedAt },
                bookmarkStore = InMemoryEntityBookmarkStore(),
                bookmarkName = "goal-relationships",
                entityProcessor = EntityProcessor.from { _: GoalRelationship -> },
                safeBoundary = boundaryOf(baseTime, readAt = baseTime.plusSeconds(1)),
                stallThreshold = Duration.ofHours(1),
                startLog = {},
                endLog = { _, _ -> },
            )

            processor.processOneBatch() shouldBe Action.Wait

            headReads shouldBe 0
        }

        it("says nothing while the rows it cannot read span less than the stall threshold") {
            val processor = stallableProcessorFor(
                listOf(goalRelationship(secondsAfterBase = 59 * 60)),
                safeBoundary = boundaryOf(baseTime, readAt = baseTime.plusSeconds(3 * 60 * 60)),
            )

            processor.processOneBatch() shouldBe Action.Wait
        }

        it("says nothing when it has nothing to do, however far behind the boundary is") {
            // an idle table is not a stall: its newest row is behind the boundary, so nothing is being forbidden
            val done = goalRelationship(1)
            val bookmarkStore = InMemoryEntityBookmarkStore()
            bookmarkStore.save("goal-relationships", done.position)
            val processor = stallableProcessorFor(
                listOf(done),
                safeBoundary = boundaryOf(baseTime.plus(Duration.ofHours(5)), readAt = baseTime.plus(Duration.ofHours(11))),
                bookmarkStore = bookmarkStore,
            )

            processor.processOneBatch() shouldBe Action.Wait
        }

        it("says nothing while it is still making progress, however far the head is beyond the boundary") {
            // the backfill case: rows readable below a boundary an unrelated transaction has pinned hours back. The
            // state is otherwise identical to a stall, so what discriminates is only whether a batch read anything.
            val readable = (1..3).map { goalRelationship(it) }
            val blocked = goalRelationship(secondsAfterBase = 3 * 60 * 60)
            val processor = stallableProcessorFor(
                readable + blocked,
                safeBoundary = boundaryOf(baseTime.plusSeconds(4), readAt = baseTime.plus(Duration.ofHours(4))),
                batchSize = 1,
            )

            repeat(3) { processor.processOneBatch() shouldBe Action.Continue }

            // and now that there is nothing left below the boundary, the same state does report
            shouldThrow<SafeBoundaryStalledException> { processor.processOneBatch() }
        }

        it("logs instead of throwing under LogAndContinue, and picks the rows up once the boundary passes them") {
            val waiting = goalRelationship(secondsAfterBase = 2 * 60 * 60)
            val logged = mutableListOf<String>()
            val processed = mutableListOf<GoalRelationship>()
            var boundary = baseTime
            val processor = stallableProcessorFor(
                listOf(waiting),
                safeBoundary = SafeBoundary { SafeBoundaryReading(boundary, boundary.plusSeconds(3 * 60 * 60)) },
                stallBehaviour = StallBehaviour.LogAndContinue { logged += it },
                processed = processed,
            )

            processor.processOneBatch() shouldBe Action.Wait
            logged.size shouldBe 1
            logged.single() shouldContain "goal-relationships"

            boundary = waiting.updatedAt.plusSeconds(1)
            processor.processOneBatch() shouldBe Action.Wait

            processed shouldBe listOf(waiting)
            logged.size shouldBe 1
        }

        it("withholds a row sitting exactly on the boundary, because that is where an open transaction's rows sit") {
            val onTheBoundary = goalRelationship(10)
            val processed = mutableListOf<GoalRelationship>()
            val bookmarkStore = InMemoryEntityBookmarkStore()
            val processor = processorFor(
                listOf(onTheBoundary),
                processed,
                bookmarkStore,
                safeBoundary = boundaryOf(onTheBoundary.updatedAt),
            )

            processor.processOneBatch()

            processed shouldBe emptyList()
            bookmarkStore.saved shouldBe emptyList()
        }

        it("returns Continue when a full batch was processed, so the caller comes straight back") {
            val goalRelationships = (1..3).map { goalRelationship(it) }
            val processed = mutableListOf<GoalRelationship>()
            val processor = processorFor(goalRelationships, processed, InMemoryEntityBookmarkStore(), batchSize = 3)

            processor.processOneBatch() shouldBe Action.Continue
        }

        it("waits without processing anything when the bookmark lock can't be obtained") {
            val processed = mutableListOf<GoalRelationship>()
            val bookmarkStore = InMemoryEntityBookmarkStore(lockObtainable = false)

            val action = processorFor(listOf(goalRelationship(1)), processed, bookmarkStore).processOneBatch()

            action shouldBe Action.Wait
            processed shouldBe emptyList()
            bookmarkStore.saved shouldBe emptyList()
        }

        it("fails loudly when the source re-returns the row the bookmark is already at") {
            val stuck = goalRelationship(1)
            val bookmarkStore = InMemoryEntityBookmarkStore()
            bookmarkStore.save("goal-relationships", stuck.position)
            val processor = BatchedAsyncEntityProcessor(
                entitySource = EntitySource.from { _, _, _ -> listOf(PositionedEntity(stuck, stuck.position)) },
                entityUpdatedAtStats = EntityUpdatedAtStats.from { stuck.updatedAt },
                bookmarkStore = bookmarkStore,
                bookmarkName = "goal-relationships",
                entityProcessor = EntityProcessor.from { _: GoalRelationship -> },
                safeBoundary = wellAfterAnyRow,
                startLog = {},
                endLog = { _, _ -> },
            )

            shouldThrow<EntitySourceStalledException> { processor.processOneBatch() }
        }

        it("fails loudly when the source returns rows out of order") {
            val earlier = goalRelationship(1)
            val later = goalRelationship(2)
            val processor = BatchedAsyncEntityProcessor(
                entitySource = EntitySource.from { _, _, _ ->
                    listOf(PositionedEntity(later, later.position), PositionedEntity(earlier, earlier.position))
                },
                entityUpdatedAtStats = EntityUpdatedAtStats.from { later.updatedAt },
                bookmarkStore = InMemoryEntityBookmarkStore(),
                bookmarkName = "goal-relationships",
                entityProcessor = EntityProcessor.from { _: GoalRelationship -> },
                safeBoundary = wellAfterAnyRow,
                startLog = {},
                endLog = { _, _ -> },
            )

            shouldThrow<EntitySourceOrderingException> { processor.processOneBatch() }
        }

        it("reports statistics for each entity processed") {
            val goalRelationships = (1..2).map { goalRelationship(it) }
            val collected = mutableListOf<PositionedEntity<*>>()
            val stats = object : EntityStatisticsCollector {
                override fun entityProcessed(processor: AsyncEntityProcessor<*>, entity: PositionedEntity<*>, durationMs: Long) {
                    collected += entity
                }
            }
            BatchedAsyncEntityProcessor(
                entitySource = InMemoryEntitySource(goalRelationships.map(::positioned)),
                entityUpdatedAtStats = InMemoryEntitySource(goalRelationships.map(::positioned)),
                bookmarkStore = InMemoryEntityBookmarkStore(),
                bookmarkName = "goal-relationships",
                entityProcessor = EntityProcessor.from { _: GoalRelationship -> },
                safeBoundary = wellAfterAnyRow,
                startLog = {},
                endLog = { _, _ -> },
                stats = stats,
            ).processOneBatch()

            collected.map { it.position } shouldBe goalRelationships.map { it.position }
        }
    }

    describe("EntityProcessor.compose") {
        it("shares one bookmark across several processors") {
            val goalRelationships = (1..2).map { goalRelationship(it) }
            val first = mutableListOf<UUID>()
            val second = mutableListOf<EntityPosition>()
            val bookmarkStore = InMemoryEntityBookmarkStore()

            BatchedAsyncEntityProcessor(
                entitySource = InMemoryEntitySource(goalRelationships.map(::positioned)),
                entityUpdatedAtStats = InMemoryEntitySource(goalRelationships.map(::positioned)),
                bookmarkStore = bookmarkStore,
                bookmarkName = "goal-relationships",
                entityProcessor = EntityProcessor.compose(
                    EntityProcessor.from { goalRelationship: GoalRelationship -> first += goalRelationship.id },
                    EntityProcessor.from { _: GoalRelationship, position: EntityPosition -> second += position },
                ),
                safeBoundary = wellAfterAnyRow,
                startLog = {},
                endLog = { _, _ -> },
            ).processOneBatch()

            first shouldBe goalRelationships.map { it.id }
            second shouldBe goalRelationships.map { it.position }
            bookmarkStore.bookmarkFor("goal-relationships").position shouldBe goalRelationships.last().position
        }
    }
})
