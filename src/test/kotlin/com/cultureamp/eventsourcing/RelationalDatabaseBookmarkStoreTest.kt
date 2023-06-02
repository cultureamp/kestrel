package com.cultureamp.eventsourcing

import io.kotest.core.spec.style.DescribeSpec
import org.jetbrains.exposed.sql.Database
import io.kotest.matchers.shouldBe
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction

class RelationalDatabaseBookmarkStoreTest : DescribeSpec({
    val db = PgTestConfig.db ?: Database.connect(url = "jdbc:h2:mem:test;MODE=MySQL;DB_CLOSE_DELAY=-1;", driver = "org.h2.Driver")
    val store = RelationalDatabaseBookmarkStore(db)

    beforeTest {
        transaction(db) {
            SchemaUtils.create(store.table)
        }
    }

    afterTest {
        transaction(db) {
            SchemaUtils.drop(store.table)
        }
    }

    describe("RelationalDatabaseBookmarkStore") {
        it("sets and retrieves a bookmark") {
            store.save(Bookmark("new-bookmark", 123L))
            store.save(Bookmark("other-bookmark", 456L))
            store.bookmarkFor("new-bookmark") shouldBe Bookmark("new-bookmark", 123L)
        }

        it("returns zero for an unknown bookmark") {
            store.bookmarkFor("other-new-bookmark") shouldBe Bookmark("other-new-bookmark", 0L)
        }

        it("updates the value if the bookmark already exists") {
            store.save(Bookmark("update-bookmark", 123L))
            store.save(Bookmark("other-bookmark", 456L))
            store.save(Bookmark("update-bookmark", 789L))
            store.bookmarkFor("update-bookmark") shouldBe Bookmark("update-bookmark", 789L)
        }

        it("can fetch bookmarks in bulk") {
            store.save(Bookmark("new-bookmark", 123L))
            store.save(Bookmark("other-bookmark", 456L))
            store.bookmarksFor(setOf("new-bookmark", "other-bookmark", "unknown-bookmark")) shouldBe setOf(
                Bookmark("new-bookmark", 123L),
                Bookmark("other-bookmark", 456L),
                Bookmark("unknown-bookmark", 0L),
            )
        }
    }
})


