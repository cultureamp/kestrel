package com.cultureamp.eventsourcing

import com.cultureamp.common.Action
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.joda.time.DateTime
import java.util.UUID

class BatchedAsyncEntityProcessorIntegrationTest : DescribeSpec({
    val db = PgTestConfig.db ?: Database.connect(url = "jdbc:h2:mem:test;MODE=MySQL;DB_CLOSE_DELAY=-1;", driver = "org.h2.Driver")
    val bookmarksTable = EntityBookmarks("entity_bookmarks_integration")
    val bookmarkStore = RelationalDatabaseEntityBookmarkStore(db, bookmarksTable)
    val baseTime = DateTime(2026, 8, 10, 9, 0, 0, 0)
    val bookmarkName = "WidgetNames"

    val entitySource = RelationalDatabaseEntitySource(
        db = db,
        table = WidgetsTable,
        updatedAtColumn = WidgetsTable.updatedAt,
        idColumn = WidgetsTable.id,
        rowToEntity = { Widget(it[WidgetsTable.id], it[WidgetsTable.name], it[WidgetsTable.updatedAt]) },
    )

    fun insertWidget(name: String, updatedAt: DateTime): Widget {
        val widgetId = UUID.randomUUID()
        transaction(db) {
            WidgetsTable.insert {
                it[id] = widgetId
                it[WidgetsTable.name] = name
                it[retired] = false
                it[WidgetsTable.updatedAt] = updatedAt
            }
        }
        return Widget(widgetId, name, updatedAt)
    }

    fun touchWidget(widget: Widget, updatedAt: DateTime) {
        transaction(db) {
            WidgetsTable.update({ WidgetsTable.id eq widget.id }) {
                it[WidgetsTable.updatedAt] = updatedAt
            }
        }
    }

    beforeTest {
        transaction(db) {
            SchemaUtils.create(WidgetsTable)
            SchemaUtils.create(bookmarksTable)
        }
    }

    afterTest {
        transaction(db) {
            SchemaUtils.drop(bookmarksTable)
            SchemaUtils.drop(WidgetsTable)
        }
    }

    describe("BatchedAsyncEntityProcessor against a real table") {
        it("processes a table, persists its position, and resumes from it") {
            val processed = mutableListOf<String>()
            val processor = BatchedAsyncEntityProcessor(
                entitySource = entitySource,
                entityUpdatedAtStats = entitySource,
                bookmarkStore = bookmarkStore,
                bookmarkName = bookmarkName,
                entityProcessor = EntityProcessor.from { widget: Widget -> processed += widget.name },
                batchSize = 2,
                clock = { baseTime.plusHours(1) },
            )
            val first = insertWidget("first", baseTime.plusSeconds(1))
            insertWidget("second", baseTime.plusSeconds(2))
            insertWidget("third", baseTime.plusSeconds(3))

            bookmarkStore.bookmarkFor(bookmarkName) shouldBe EntityBookmark(bookmarkName, null)

            processor.processOneBatch() shouldBe Action.Continue
            processed shouldBe listOf("first", "second")
            bookmarkStore.bookmarkFor(bookmarkName).position?.updatedAt?.millis shouldBe baseTime.plusSeconds(2).millis

            processor.processOneBatch() shouldBe Action.Wait
            processed shouldBe listOf("first", "second", "third")

            // nothing new to do, and crucially the persisted position round-trips well enough not to re-read the last row
            processor.processOneBatch() shouldBe Action.Wait
            processed shouldBe listOf("first", "second", "third")

            // a row that gets touched is picked up again
            touchWidget(first, baseTime.plusSeconds(4))
            processor.processOneBatch() shouldBe Action.Wait
            processed shouldBe listOf("first", "second", "third", "first")
        }

        it("does not read rows that are within the timestamp delay of now") {
            val processed = mutableListOf<String>()
            var now = baseTime.plusSeconds(10)
            val processor = BatchedAsyncEntityProcessor(
                entitySource = entitySource,
                entityUpdatedAtStats = entitySource,
                bookmarkStore = bookmarkStore,
                bookmarkName = bookmarkName,
                entityProcessor = EntityProcessor.from { widget: Widget -> processed += widget.name },
                timestampDelayMs = 5_000,
                clock = { now },
            )
            insertWidget("settled", baseTime.plusSeconds(1))
            insertWidget("in-flight", baseTime.plusSeconds(9))

            processor.processOneBatch()
            processed shouldBe listOf("settled")

            now = baseTime.plusSeconds(20)
            processor.processOneBatch()
            processed shouldBe listOf("settled", "in-flight")
        }

        it("reports lag against the head of the table") {
            val processor = BatchedAsyncEntityProcessor(
                entitySource = entitySource,
                entityUpdatedAtStats = entitySource,
                bookmarkStore = bookmarkStore,
                bookmarkName = bookmarkName,
                entityProcessor = EntityProcessor.from { _: Widget -> },
                batchSize = 1,
                clock = { baseTime.plusHours(1) },
            )
            insertWidget("first", baseTime.plusSeconds(1))
            insertWidget("second", baseTime.plusSeconds(11))
            var lag: EntityLag? = null
            val monitor = AsyncEntityProcessorMonitor(listOf(processor), { lag = it }, clock = { baseTime.plusSeconds(31) })

            processor.processOneBatch()
            monitor.run()
            lag!!.lagMs shouldBe 10_000
            lag!!.latencyMs shouldBe 30_000

            processor.processOneBatch()
            monitor.run()
            lag!!.lagMs shouldBe 0
            lag!!.latencyMs shouldBe 20_000
        }
    }
})
