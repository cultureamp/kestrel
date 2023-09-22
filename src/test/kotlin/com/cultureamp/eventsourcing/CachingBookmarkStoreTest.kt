package com.cultureamp.eventsourcing

import arrow.core.nonEmptySetOf
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction

class CachingBookmarkStoreTest : DescribeSpec({
    val db = PgTestConfig.db ?: Database.connect(url = "jdbc:h2:mem:test;MODE=MySQL;DB_CLOSE_DELAY=-1;", driver = "org.h2.Driver")
    val store = RelationalDatabaseBookmarkStore(db)
    val cachedStore = CachingBookmarkStore(store)

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
            cachedStore.save(Bookmark(BookmarkName("new-bookmark"), 123L))
            cachedStore.save(Bookmark(BookmarkName("other-bookmark"), 456L))
            cachedStore.bookmarkFor(BookmarkName("new-bookmark")) shouldBe Bookmark(BookmarkName("new-bookmark"), 123L)
        }

        it("returns zero for an unknown bookmark") {
            cachedStore.bookmarkFor(BookmarkName("other-new-bookmark")) shouldBe Bookmark(
                BookmarkName("other-new-bookmark"),
                0L
            )
        }

        it("updates the value if the bookmark already exists") {
            cachedStore.save(Bookmark(BookmarkName("update-bookmark"), 123L))
            cachedStore.save(Bookmark(BookmarkName("other-bookmark"), 456L))
            cachedStore.save(Bookmark(BookmarkName("update-bookmark"), 789L))
            cachedStore.bookmarkFor(BookmarkName("update-bookmark")) shouldBe Bookmark(
                BookmarkName("update-bookmark"),
                789L
            )
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


