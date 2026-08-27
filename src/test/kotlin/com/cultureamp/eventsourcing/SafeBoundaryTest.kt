package com.cultureamp.eventsourcing

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.time.Duration
import java.time.LocalDateTime

/**
 * The parts of the boundary that need no database. The refusals are checked here rather than in
 * [PostgresXactStartSafeBoundaryTest] because provoking them for real needs a server-level setting
 * (`max_prepared_transactions`) or an unprivileged role, and because they have to hold on any Postgres, not just one
 * that can be configured to demonstrate them.
 */
class SafeBoundaryTest : DescribeSpec({
    val boundary = LocalDateTime.of(2026, 8, 10, 9, 0, 0, 0)

    describe("Observation.validated") {
        it("returns the boundary when nothing is hidden and no prepared transactions are configured") {
            PostgresXactStartSafeBoundary.Observation(boundary, redactedBackends = 0, maxPreparedTransactions = 0)
                .validated() shouldBe boundary
        }

        it("refuses to report a boundary when prepared transactions are enabled") {
            // a prepared transaction is not an ordinary backend, so pg_stat_activity cannot show it to min(xact_start)
            val exception = shouldThrow<SafeBoundaryUnsupportedException> {
                PostgresXactStartSafeBoundary.Observation(boundary, redactedBackends = 0, maxPreparedTransactions = 10)
                    .validated()
            }

            exception.message!! shouldContain "max_prepared_transactions is 10"
        }

        it("refuses to report a boundary when backends are hidden from the reader") {
            val exception = shouldThrow<SafeBoundaryUnreliableException> {
                PostgresXactStartSafeBoundary.Observation(boundary, redactedBackends = 2, maxPreparedTransactions = 0)
                    .validated()
            }

            exception.message!! shouldContain "2 backend(s)"
        }

        it("reports the prepared-transaction problem first, since it cannot be fixed by a grant") {
            shouldThrow<SafeBoundaryUnsupportedException> {
                PostgresXactStartSafeBoundary.Observation(boundary, redactedBackends = 2, maxPreparedTransactions = 10)
                    .validated()
            }
        }
    }

    describe("unsafeFixedDelay") {
        it("holds back by the delay") {
            val now = boundary.plusSeconds(30)

            SafeBoundary.unsafeFixedDelay(Duration.ofSeconds(10)) { now }.safeBefore() shouldBe now.minusSeconds(10)
        }

        it("has no blocker diagnosis to offer") {
            SafeBoundary.unsafeFixedDelay(Duration.ofSeconds(10)).describeBlockers() shouldContain "no blocker diagnosis"
        }
    }
})
