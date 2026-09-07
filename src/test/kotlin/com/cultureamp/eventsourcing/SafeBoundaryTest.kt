package com.cultureamp.eventsourcing

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
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
                .validated() shouldBe SafeBoundaryReading(boundary, boundary)
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

        it("refuses to report a boundary from a standby, whose pg_stat_activity does not contain the primary's writers") {
            val exception = shouldThrow<SafeBoundaryUnsupportedException> {
                PostgresXactStartSafeBoundary.Observation(boundary, redactedBackends = 0, maxPreparedTransactions = 0, inRecovery = true)
                    .validated()
            }

            exception.message!! shouldContain "standby"
            exception.message!! shouldContain "targetServerType=primary"
        }

        it("refuses to report a boundary when a backend has track_activities off, since it publishes no xact_start") {
            val exception = shouldThrow<SafeBoundaryUnreliableException> {
                PostgresXactStartSafeBoundary.Observation(boundary, redactedBackends = 0, maxPreparedTransactions = 0, untrackedBackends = 1)
                    .validated()
            }

            exception.message!! shouldContain "1 backend(s)"
            exception.message!! shouldContain "track_activities"
        }

        it("reports the prepared-transaction problem first, since it cannot be fixed by a grant") {
            shouldThrow<SafeBoundaryUnsupportedException> {
                PostgresXactStartSafeBoundary.Observation(boundary, redactedBackends = 2, maxPreparedTransactions = 10)
                    .validated()
            }
        }
    }

    describe("describeBlockers") {
        it("says it has no diagnosis to offer, rather than implying nothing is blocking") {
            SafeBoundary { SafeBoundaryReading(boundary, boundary) }.describeBlockers() shouldContain "no blocker diagnosis"
        }
    }
})
