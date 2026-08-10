package com.cultureamp.eventsourcing

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import org.joda.time.DateTime
import java.util.UUID

class RelationalDatabaseEntityBookmarkStoreTest : DescribeSpec({
    val db = PgTestConfig.db ?: Database.connect(url = "jdbc:h2:mem:test;MODE=MySQL;DB_CLOSE_DELAY=-1;", driver = "org.h2.Driver")
    val store = RelationalDatabaseEntityBookmarkStore(db)
    val baseTime = DateTime(2026, 8, 10, 9, 0, 0, 0)

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
            store.save(EntityBookmark("new-bookmark", position))
            store.save(EntityBookmark("other-bookmark", EntityPosition(baseTime.plusSeconds(1), UUID.randomUUID())))

            store.bookmarkFor("new-bookmark") shouldBe EntityBookmark("new-bookmark", position)
        }

        it("returns a null position for an unknown bookmark") {
            store.bookmarkFor("other-new-bookmark") shouldBe EntityBookmark("other-new-bookmark", null)
        }

        it("updates the position if the bookmark already exists") {
            val first = EntityPosition(baseTime, UUID.randomUUID())
            val second = EntityPosition(baseTime.plusHours(1), UUID.randomUUID())
            store.save(EntityBookmark("update-bookmark", first))
            store.save(EntityBookmark("other-bookmark", EntityPosition(baseTime, UUID.randomUUID())))
            store.save(EntityBookmark("update-bookmark", second))

            store.bookmarkFor("update-bookmark") shouldBe EntityBookmark("update-bookmark", second)
        }

        it("rejects saving a bookmark with no position, since a stored bookmark always sits on a row") {
            shouldThrow<IllegalArgumentException> { store.save(EntityBookmark("unstarted-bookmark", null)) }

            store.bookmarkFor("unstarted-bookmark") shouldBe EntityBookmark("unstarted-bookmark", null)
        }

        it("can fetch bookmarks in bulk") {
            val position = EntityPosition(baseTime, UUID.randomUUID())
            val otherPosition = EntityPosition(baseTime.plusSeconds(1), UUID.randomUUID())
            store.save(EntityBookmark("new-bookmark", position))
            store.save(EntityBookmark("other-bookmark", otherPosition))

            val bookmarks = store.bookmarksFor(setOf("new-bookmark", "other-bookmark", "unknown-bookmark"))

            bookmarks shouldBe setOf(
                EntityBookmark("new-bookmark", position),
                EntityBookmark("other-bookmark", otherPosition),
                EntityBookmark("unknown-bookmark", null),
            )
        }

        it("checks out a bookmark, obtaining the lock") {
            val position = EntityPosition(baseTime, UUID.randomUUID())
            store.save(EntityBookmark("checkout-bookmark", position))

            val checkedOut = store.checkoutBookmark("checkout-bookmark")

            (checkedOut as Right).value shouldBe EntityBookmark("checkout-bookmark", position)
        }
    }
})
