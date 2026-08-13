package com.cultureamp.eventsourcing

import com.cultureamp.common.Action
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.time.Duration
import java.time.Instant
import java.util.UUID

class BatchedAsyncEntityProcessorTest : DescribeSpec({
    val baseTime = Instant.parse("2026-08-10T09:00:00Z")
    val wellAfterAnyRow = SafeBoundary { baseTime.plus(Duration.ofHours(1)) }
    val accountId = UUID.randomUUID()

    fun goalRelationship(secondsAfterBase: Int, id: UUID = UUID.randomUUID()) =
        GoalRelationship(id, UUID.randomUUID(), UUID.randomUUID(), accountId, createdAt = fixtureCreatedAt, updatedAt = baseTime.plusSeconds(secondsAfterBase.toLong()))

    fun positioned(goalRelationship: GoalRelationship) = PositionedEntity(goalRelationship, goalRelationship.position)

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

        it("throws once it has been held back by the boundary for longer than the stall threshold") {
            val waiting = goalRelationship(10)
            var now = baseTime
            val processor = BatchedAsyncEntityProcessor(
                entitySource = InMemoryEntitySource(listOf(positioned(waiting))),
                entityUpdatedAtStats = InMemoryEntitySource(listOf(positioned(waiting))),
                bookmarkStore = InMemoryEntityBookmarkStore(),
                bookmarkName = "goal-relationships",
                entityProcessor = EntityProcessor.from { _: GoalRelationship -> },
                // pinned well before the waiting row, as an open transaction would pin it
                safeBoundary = object : SafeBoundary {
                    override fun safeBefore() = baseTime
                    override fun describeBlockers() = "pid=999 application_name='psql' [UNRECOGNISED]"
                },
                stallThreshold = Duration.ofHours(1),
                clock = { now },
                startLog = {},
                endLog = { _, _ -> },
            )

            // held back, but not yet for long enough to complain about
            processor.processOneBatch() shouldBe Action.Wait
            now = baseTime.plus(Duration.ofMinutes(59))
            processor.processOneBatch() shouldBe Action.Wait

            now = baseTime.plus(Duration.ofMinutes(61))
            val exception = shouldThrow<SafeBoundaryStalledException> { processor.processOneBatch() }

            // the report has to name the session to go and close, or it just says "publishing is behind"
            exception.message!! shouldContain "application_name='psql'"
            exception.message!! shouldContain "goal-relationships"
        }

        it("does not throw when it has nothing to do, however old the boundary is") {
            // an idle table is not a stall: everything in it is already behind the boundary
            val done = goalRelationship(1)
            val bookmarkStore = InMemoryEntityBookmarkStore()
            bookmarkStore.save("goal-relationships", done.position)
            var now = baseTime
            val processor = BatchedAsyncEntityProcessor(
                entitySource = InMemoryEntitySource(listOf(positioned(done))),
                entityUpdatedAtStats = InMemoryEntitySource(listOf(positioned(done))),
                bookmarkStore = bookmarkStore,
                bookmarkName = "goal-relationships",
                entityProcessor = EntityProcessor.from { _: GoalRelationship -> },
                safeBoundary = SafeBoundary { baseTime.plus(Duration.ofHours(5)) },
                stallThreshold = Duration.ofHours(1),
                clock = { now },
                startLog = {},
                endLog = { _, _ -> },
            )

            processor.processOneBatch()
            now = baseTime.plus(Duration.ofDays(1))

            processor.processOneBatch() shouldBe Action.Wait
        }

        it("does not throw while it is still making progress, however far behind the boundary is") {
            // the backfill case: a boundary pinned hours in the past by an unrelated transaction, while millions of
            // older rows are read perfectly happily. Throwing here would abort exactly the run people are watching.
            val rows = (1..5).map { goalRelationship(it) }
            var now = baseTime
            val processor = BatchedAsyncEntityProcessor(
                entitySource = InMemoryEntitySource(rows.map(::positioned)),
                entityUpdatedAtStats = InMemoryEntitySource(rows.map(::positioned)),
                bookmarkStore = InMemoryEntityBookmarkStore(),
                bookmarkName = "goal-relationships",
                entityProcessor = EntityProcessor.from { _: GoalRelationship -> },
                // above rows 1-3, below rows 4-5, so there is always work waiting beyond the boundary
                safeBoundary = SafeBoundary { baseTime.plusSeconds(4) },
                stallThreshold = Duration.ofHours(1),
                clock = { now },
                batchSize = 1,
                startLog = {},
                endLog = { _, _ -> },
            )

            repeat(3) {
                now = now.plus(Duration.ofHours(1))
                processor.processOneBatch() shouldBe Action.Continue
            }
        }

        it("withholds a row sitting exactly on the boundary, because that is where an open transaction's rows sit") {
            val onTheBoundary = goalRelationship(10)
            val processed = mutableListOf<GoalRelationship>()
            val bookmarkStore = InMemoryEntityBookmarkStore()
            val processor = processorFor(
                listOf(onTheBoundary),
                processed,
                bookmarkStore,
                safeBoundary = SafeBoundary { onTheBoundary.updatedAt },
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
