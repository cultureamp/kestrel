package com.cultureamp.eventsourcing

import io.kotest.core.spec.style.DescribeSpec
import org.jetbrains.exposed.sql.Database
import io.kotest.matchers.shouldBe
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
            cachedStore.save(Bookmark("new-bookmark", 123L))
            cachedStore.save(Bookmark("other-bookmark", 456L))
            cachedStore.bookmarkFor("new-bookmark") shouldBe Bookmark("new-bookmark", 123L)
        }

        it("returns zero for an unknown bookmark") {
            cachedStore.bookmarkFor("other-new-bookmark") shouldBe Bookmark("other-new-bookmark", 0L)
        }

        it("updates the value if the bookmark already exists") {
            cachedStore.save(Bookmark("update-bookmark", 123L))
            cachedStore.save(Bookmark("other-bookmark", 456L))
            cachedStore.save(Bookmark("update-bookmark", 789L))
            cachedStore.bookmarkFor("update-bookmark") shouldBe Bookmark("update-bookmark", 789L)
        }
    }
})


