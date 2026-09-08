package com.cultureamp.eventsourcing

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.Duration
import java.time.LocalDateTime
import java.util.TimeZone
import java.util.UUID

class RelationalDatabaseEntityBookmarkStoreTest : DescribeSpec({
    val db = PgTestConfig.db ?: Database.connect(url = "jdbc:h2:mem:test;MODE=MySQL;DB_CLOSE_DELAY=-1;", driver = "org.h2.Driver")
    val store = RelationalDatabaseEntityBookmarkStore(db)
    val baseTime = LocalDateTime.of(2026, 8, 10, 9, 0, 0, 0)

    beforeTest {
        store.createSchemaIfNotExists()
    }

    afterTest {
        transaction(db) {
            SchemaUtils.drop(store.table)
        }
    }

    describe("RelationalDatabaseEntityBookmarkStore") {
        it("sets and retrieves a bookmark position") {
            val position = EntityPosition(baseTime, UUID.randomUUID())
            store.save("new-bookmark", position)
            store.save("other-bookmark", EntityPosition(baseTime.plusSeconds(1), UUID.randomUUID()))

            store.bookmarkFor("new-bookmark") shouldBe EntityBookmark("new-bookmark", position)
        }

        it("returns a null position for an unknown bookmark") {
            store.bookmarkFor("other-new-bookmark") shouldBe EntityBookmark("other-new-bookmark", null)
        }

        it("updates the position if the bookmark already exists") {
            val first = EntityPosition(baseTime, UUID.randomUUID())
            val second = EntityPosition(baseTime.plus(Duration.ofHours(1)), UUID.randomUUID())
            store.save("update-bookmark", first)
            store.save("other-bookmark", EntityPosition(baseTime, UUID.randomUUID()))
            store.save("update-bookmark", second)

            store.bookmarkFor("update-bookmark") shouldBe EntityBookmark("update-bookmark", second)
        }

        it("can fetch bookmarks in bulk") {
            val position = EntityPosition(baseTime, UUID.randomUUID())
            val otherPosition = EntityPosition(baseTime.plusSeconds(1), UUID.randomUUID())
            store.save("new-bookmark", position)
            store.save("other-bookmark", otherPosition)

            val bookmarks = store.bookmarksFor(setOf("new-bookmark", "other-bookmark", "unknown-bookmark"))

            bookmarks shouldBe setOf(
                EntityBookmark("new-bookmark", position),
                EntityBookmark("other-bookmark", otherPosition),
                EntityBookmark("unknown-bookmark", null),
            )
        }

        it("namespaces the name it locks on, so it cannot lock out an event-bookmark of the same name") {
            // advisory locks are one flat key-space per database, and the key is a hash of the name alone
            val locked = mutableListOf<String>()
            val recordingLock = object : BookmarkLock {
                override fun tryLock(bookmark: Bookmark) = true
                override fun tryLock(bookmarkName: String) = true.also { locked += bookmarkName }
                override fun close() = Unit
            }

            RelationalDatabaseEntityBookmarkStore(db, store.table, recordingLock).checkoutBookmark("shared-name")

            locked shouldBe listOf("entity:shared-name")
        }

        it("stores a position in the hour a DST transition skips exactly, whatever the JVM's default zone") {
            // Exposed's datetime type would save 02:30 as 03:30 here, an hour ahead of the row it came from, and every
            // row stamped in that hour would then sit below the bookmark
            val inTheGap = EntityPosition(LocalDateTime.of(2026, 10, 4, 2, 30), UUID.randomUUID())
            val defaultZone = TimeZone.getDefault()
            TimeZone.setDefault(TimeZone.getTimeZone("Australia/Melbourne"))
            try {
                store.save("gap-bookmark", inTheGap)

                store.bookmarkFor("gap-bookmark") shouldBe EntityBookmark("gap-bookmark", inTheGap)
            } finally {
                TimeZone.setDefault(defaultZone)
            }
        }

        it("checks out a bookmark, obtaining the lock") {
            val position = EntityPosition(baseTime, UUID.randomUUID())
            store.save("checkout-bookmark", position)

            val checkedOut = store.checkoutBookmark("checkout-bookmark")

            (checkedOut as Right).value shouldBe EntityBookmark("checkout-bookmark", position)
        }
    }
})
