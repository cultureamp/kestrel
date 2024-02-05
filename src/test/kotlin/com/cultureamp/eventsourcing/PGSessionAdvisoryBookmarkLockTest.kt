package com.cultureamp.eventsourcing

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import java.sql.DriverManager
import java.util.Properties

class PGSessionAdvisoryBookmarkLockTest : DescribeSpec({

    val connector = {
        DriverManager.getConnection(
            "jdbc:tc:postgresql:14.3:///PGSessionAdvisoryBookmarkLockTest",
            Properties().also {
                it.setProperty("username", "test")
                it.setProperty("password", "password")
            }
        )
    }

    val testBookmark = Bookmark("testBookmark", 1)

    describe("PGSessionAdvisoryBookmarkLock") {
        it("acquires the lock if it doesn't have it") {
            PGSessionAdvisoryBookmarkLock(connector).use { locker ->
                locker.tryLock(testBookmark) shouldBe true
                locker.tryLock(testBookmark) shouldBe true
            }
        }

        it("doesn't  acquire the lock if something else has it") {
            PGSessionAdvisoryBookmarkLock(connector).use { otherLocker ->
                otherLocker.tryLock(testBookmark) shouldBe true
                PGSessionAdvisoryBookmarkLock(connector).use { locker ->
                    locker.tryLock(testBookmark) shouldBe false
                }
            }
        }

        it("releases the lock if the lock is closed") {
            PGSessionAdvisoryBookmarkLock(connector).use { otherLocker ->
                PGSessionAdvisoryBookmarkLock(connector).use { locker ->
                    locker.tryLock(testBookmark) shouldBe true
                    otherLocker.tryLock(testBookmark) shouldBe false
                }
                otherLocker.tryLock(testBookmark) shouldBe true
            }
        }

        it("releases the lock if the connection is closed, and can retrieve it after new connection established") {
            PGSessionAdvisoryBookmarkLock(connector).use { otherLocker ->
                PGSessionAdvisoryBookmarkLock(connector).use { locker ->
                    locker.tryLock(testBookmark) shouldBe true
                    otherLocker.tryLock(testBookmark) shouldBe false

                    locker.connection.close()
                    otherLocker.tryLock(testBookmark) shouldBe true
                    locker.tryLock(testBookmark) shouldBe false

                    otherLocker.close()
                    locker.tryLock(testBookmark) shouldBe true
                }
            }
        }
    }

})