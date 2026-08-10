package com.cultureamp.eventsourcing

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.jodatime.datetime
import org.jetbrains.exposed.sql.transactions.transaction
import org.joda.time.DateTime
import java.util.UUID

object WidgetsTable : Table("entity_widgets") {
    val id = uuid("id")
    val name = varchar("name", 100)
    val retired = bool("retired")
    val updatedAt = datetime("updated_at")
    override val primaryKey = PrimaryKey(id)

    init {
        index(false, updatedAt, id)
    }
}

class RelationalDatabaseEntitySourceTest : DescribeSpec({
    val db = PgTestConfig.db ?: Database.connect(url = "jdbc:h2:mem:test;MODE=MySQL;DB_CLOSE_DELAY=-1;", driver = "org.h2.Driver")
    val baseTime = DateTime(2026, 8, 10, 9, 0, 0, 0)
    val distantFuture = baseTime.plusYears(1)

    val entitySource = RelationalDatabaseEntitySource(
        db = db,
        table = WidgetsTable,
        updatedAtColumn = WidgetsTable.updatedAt,
        idColumn = WidgetsTable.id,
        rowToEntity = { Widget(it[WidgetsTable.id], it[WidgetsTable.name], it[WidgetsTable.updatedAt]) },
    )

    fun insertWidget(name: String, updatedAt: DateTime, retired: Boolean = false): Widget {
        val widgetId = UUID.randomUUID()
        transaction(db) {
            WidgetsTable.insert {
                it[id] = widgetId
                it[WidgetsTable.name] = name
                it[WidgetsTable.retired] = retired
                it[WidgetsTable.updatedAt] = updatedAt
            }
        }
        return Widget(widgetId, name, updatedAt)
    }

    beforeTest {
        transaction(db) {
            SchemaUtils.create(WidgetsTable)
        }
    }

    afterTest {
        transaction(db) {
            SchemaUtils.drop(WidgetsTable)
        }
    }

    describe("getAfter") {
        it("returns rows in updated-at order from the beginning when there is no position") {
            insertWidget("third", baseTime.plusSeconds(3))
            insertWidget("first", baseTime.plusSeconds(1))
            insertWidget("second", baseTime.plusSeconds(2))

            entitySource.getAfter(null, distantFuture).map { it.entity.name } shouldBe listOf("first", "second", "third")
        }

        it("returns only rows strictly after the given position") {
            insertWidget("first", baseTime.plusSeconds(1))
            val second = insertWidget("second", baseTime.plusSeconds(2))
            insertWidget("third", baseTime.plusSeconds(3))

            entitySource.getAfter(second.position, distantFuture).map { it.entity.name } shouldBe listOf("third")
        }

        it("positions each row by its own updated-at and id") {
            val only = insertWidget("only", baseTime.plusSeconds(1))

            val read = entitySource.getAfter(null, distantFuture).single()

            read.position.id shouldBe only.id
            read.position.updatedAt.millis shouldBe only.updatedAt.millis
        }

        it("excludes rows updated after the upTo cutoff") {
            insertWidget("old", baseTime.plusSeconds(1))
            insertWidget("new", baseTime.plusSeconds(30))

            entitySource.getAfter(null, baseTime.plusSeconds(10)).map { it.entity.name } shouldBe listOf("old")
        }

        it("returns at most batchSize rows") {
            (1..5).forEach { insertWidget("widget-$it", baseTime.plusSeconds(it)) }

            entitySource.getAfter(null, distantFuture, batchSize = 2).map { it.entity.name } shouldBe listOf("widget-1", "widget-2")
        }

        it("uses the id as a tiebreaker so rows sharing an updated-at are each returned exactly once") {
            val sharedTimestamp = baseTime.plusSeconds(1)
            val inserted = (1..5).map { insertWidget("widget-$it", sharedTimestamp) }

            val seen = mutableListOf<String>()
            var position: EntityPosition? = null
            while (true) {
                val batch = entitySource.getAfter(position, distantFuture, batchSize = 2)
                if (batch.isEmpty()) break
                seen += batch.map { it.entity.name }
                position = batch.last().position
            }

            seen.size shouldBe 5
            seen.toSet() shouldBe inserted.map { it.name }.toSet()
        }

        it("applies an additional filter") {
            insertWidget("live", baseTime.plusSeconds(1))
            insertWidget("retired", baseTime.plusSeconds(2), retired = true)
            val liveOnly = RelationalDatabaseEntitySource(
                db = db,
                table = WidgetsTable,
                updatedAtColumn = WidgetsTable.updatedAt,
                idColumn = WidgetsTable.id,
                filter = { WidgetsTable.retired eq false },
                rowToEntity = { it[WidgetsTable.name] },
            )

            liveOnly.getAfter(null, distantFuture).map { it.entity } shouldBe listOf("live")
        }
    }

    describe("lastUpdatedAt") {
        it("returns null when the table is empty") {
            entitySource.lastUpdatedAt() shouldBe null
        }

        it("returns the newest updated-at in the table") {
            insertWidget("first", baseTime.plusSeconds(1))
            insertWidget("newest", baseTime.plusSeconds(30))
            insertWidget("second", baseTime.plusSeconds(2))

            entitySource.lastUpdatedAt()?.millis shouldBe baseTime.plusSeconds(30).millis
        }

        it("respects the filter when finding the newest row") {
            insertWidget("live", baseTime.plusSeconds(1))
            insertWidget("retired", baseTime.plusSeconds(30), retired = true)
            val liveOnly = RelationalDatabaseEntitySource(
                db = db,
                table = WidgetsTable,
                updatedAtColumn = WidgetsTable.updatedAt,
                idColumn = WidgetsTable.id,
                filter = { WidgetsTable.retired eq false },
                rowToEntity = { it[WidgetsTable.name] },
            )

            liveOnly.lastUpdatedAt()?.millis shouldBe baseTime.plusSeconds(1).millis
        }
    }

    describe("Op.TRUE default filter") {
        it("reads everything by default") {
            insertWidget("live", baseTime.plusSeconds(1))
            insertWidget("retired", baseTime.plusSeconds(2), retired = true)
            val unfiltered = RelationalDatabaseEntitySource(
                db = db,
                table = WidgetsTable,
                updatedAtColumn = WidgetsTable.updatedAt,
                idColumn = WidgetsTable.id,
                filter = { Op.TRUE },
                rowToEntity = { it[WidgetsTable.name] },
            )

            unfiltered.getAfter(null, distantFuture).map { it.entity } shouldBe listOf("live", "retired")
        }
    }
})
