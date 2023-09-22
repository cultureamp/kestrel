package com.cultureamp.eventsourcing

import arrow.core.nonEmptySetOf
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction

class RelationalDatabaseBookmarkStoreTest : DescribeSpec({
    val db = PgTestConfig.db ?: Database.connect(url = "jdbc:h2:mem:test;MODE=MySQL;DB_CLOSE_DELAY=-1;", driver = "org.h2.Driver")
    val store = RelationalDatabaseBookmarkStore(db)

    beforeTest {
        store.createSchemaIfNotExists()
    }

    afterTest {
        transaction(db) {
            SchemaUtils.drop(store.table)
        }
    }

    describe("RelationalDatabaseBookmarkStore") {
        it("sets and retrieves a bookmark") {
            store.save(Bookmark(BookmarkName("new-bookmark"), 123L))
            store.save(Bookmark(BookmarkName("other-bookmark"), 456L))
            store.bookmarkFor(BookmarkName("new-bookmark")) shouldBe Bookmark(BookmarkName("new-bookmark"), 123L)
        }

        it("returns zero for an unknown bookmark") {
            store.bookmarkFor(BookmarkName("other-new-bookmark")) shouldBe Bookmark(
                BookmarkName("other-new-bookmark"),
                0L
            )
        }

        it("updates the value if the bookmark already exists") {
            store.save(Bookmark(BookmarkName("update-bookmark"), 123L))
            store.save(Bookmark(BookmarkName("other-bookmark"), 456L))
            store.save(Bookmark(BookmarkName("update-bookmark"), 789L))
            store.bookmarkFor(BookmarkName("update-bookmark")) shouldBe Bookmark(BookmarkName("update-bookmark"), 789L)
        }

        it("can fetch bookmarks in bulk") {
            store.save(Bookmark(BookmarkName("new-bookmark"), 123L))
            store.save(Bookmark(BookmarkName("other-bookmark"), 456L))
            store.bookmarksFor(
                nonEmptySetOf(
                    BookmarkName("new-bookmark"),
                    BookmarkName("other-bookmark"),
                    BookmarkName("unknown-bookmark")
                )
            ) shouldBe setOf(
                Bookmark(BookmarkName("new-bookmark"), 123L),
                Bookmark(BookmarkName("other-bookmark"), 456L),
                Bookmark(BookmarkName("unknown-bookmark"), 0L),
            )
        }
    }
})


