package com.cultureamp.eventsourcing

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.testcontainers.containers.PostgreSQLContainer
import java.sql.Connection
import java.sql.DriverManager
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime

/**
 * Where the Postgres under test comes from. Testcontainers by default, so this runs in CI unattended; an existing
 * database if `KESTREL_TEST_PG_URL` is set, so it can also be run where Docker is unavailable.
 */
private data class PostgresTarget(val jdbcUrl: String, val username: String, val password: String) {
    val databaseName: String get() = jdbcUrl.substringAfterLast('/').substringBefore('?')

    companion object {
        fun resolve(): PostgresTarget = System.getenv("KESTREL_TEST_PG_URL")?.let {
            PostgresTarget(it, System.getenv("KESTREL_TEST_PG_USER") ?: "postgres", System.getenv("KESTREL_TEST_PG_PASSWORD") ?: "")
        } ?: PostgreSQLContainer("postgres:14.3").let { container ->
            container.start()
            PostgresTarget(container.jdbcUrl, container.username, container.password)
        }
    }
}

/**
 * Needs a real Postgres, since the whole mechanism is `pg_stat_activity` and H2 has no equivalent.
 *
 * The role this connects as must be able to see other backends' `xact_start` — a superuser, or a member of
 * `pg_read_all_stats`. That is not incidental to the test setup: it is the deployment requirement the boundary asserts,
 * and one of the cases below covers what happens when it does not hold.
 */
class PostgresXactStartSafeBoundaryTest : DescribeSpec({
    val postgres = PostgresTarget.resolve()
    val db = Database.connect(
        url = postgres.jdbcUrl,
        driver = "org.postgresql.Driver",
        user = postgres.username,
        password = postgres.password,
    )

    val noMargin = PostgresXactStartSafeBoundary(db, margin = Duration.ZERO)

    fun databaseNow(): Instant = transaction(db) {
        exec("SELECT clock_timestamp()") { rs ->
            rs.next()
            rs.getObject(1, OffsetDateTime::class.java).toInstant()
        }!!
    }

    /**
     * Opens a transaction on its own connection and leaves it open, returning the connection and the `xact_start`
     * Postgres assigned it. The `SELECT 1` matters: with autocommit off, JDBC only begins the transaction — and so only
     * sets `xact_start` — on the first statement.
     */
    fun openTransaction(): Pair<Connection, Instant> {
        val connection = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)
        connection.autoCommit = false
        connection.createStatement().use { it.executeQuery("SELECT 1").close() }
        val xactStart = connection.createStatement().use { statement ->
            statement.executeQuery("SELECT xact_start FROM pg_stat_activity WHERE pid = pg_backend_pid()").use { rs ->
                rs.next()
                rs.getObject(1, OffsetDateTime::class.java).toInstant()
            }
        }
        return connection to xactStart
    }

    describe("safeBefore") {
        it("tracks the database clock when nothing else is open") {
            val before = databaseNow()
            val boundary = noMargin.safeBefore()
            val after = databaseNow()

            // nothing else is open, so everything committed is visible and the boundary is simply "now"
            boundary.isAfter(before).shouldBeTrue()
            boundary.isBefore(after).shouldBeTrue()
        }

        it("never advances past the start of an open transaction, and does once it commits") {
            val (connection, xactStart) = openTransaction()

            try {
                // The invariant: the reader is never told it is safe to pass a transaction that could still commit rows
                // stamped at its start time. Equality is the expected result with a zero margin, and is safe because
                // the boundary is exclusive — an EntitySource compares `updated_at < safeBefore`, so a row stamped at
                // exactly this transaction's xact_start is still excluded. This is why that comparison must be strict.
                noMargin.safeBefore() shouldBeLessThanOrEqualTo xactStart
            } finally {
                connection.commit()
                connection.close()
            }

            noMargin.safeBefore() shouldBeGreaterThanOrEqualTo xactStart
        }

        it("holds the boundary at the oldest open transaction, not the newest") {
            val (oldest, oldestXactStart) = openTransaction()
            val (newer, newerXactStart) = openTransaction()

            try {
                newerXactStart shouldBeGreaterThanOrEqualTo oldestXactStart
                noMargin.safeBefore() shouldBeLessThanOrEqualTo oldestXactStart
            } finally {
                listOf(oldest, newer).forEach {
                    it.commit()
                    it.close()
                }
            }
        }

        it("ignores the reader's own transaction, so an idle database still advances") {
            // the boundary read is itself a transaction. If it counted itself it could never report a boundary later
            // than its own start, and an otherwise-idle database would sit still.
            val first = noMargin.safeBefore()
            val second = noMargin.safeBefore()

            second shouldBeGreaterThanOrEqualTo first
        }

        it("subtracts the margin") {
            val margin = Duration.ofSeconds(30)
            val withMargin = PostgresXactStartSafeBoundary(db, margin = margin)

            val boundary = withMargin.safeBefore()

            boundary shouldBeLessThan databaseNow().minus(margin.dividedBy(2))
        }

        it("names the open transactions holding it back, tagging ours from anyone else's") {
            // application_name is how an app distinguishes itself from somebody with a psql session. It is
            // self-reported, so it is only ever a diagnostic — never an input to the boundary.
            val connection = DriverManager.getConnection(
                "${postgres.jdbcUrl}${if (postgres.jdbcUrl.contains('?')) "&" else "?"}ApplicationName=some-service",
                postgres.username,
                postgres.password,
            )
            try {
                connection.autoCommit = false
                connection.createStatement().use { it.executeQuery("SELECT 1").close() }

                PostgresXactStartSafeBoundary(db, knownApplicationNames = setOf("some-service"))
                    .describeBlockers() shouldContain "application_name='some-service' [known]"

                PostgresXactStartSafeBoundary(db, knownApplicationNames = emptySet())
                    .describeBlockers() shouldContain "application_name='some-service' [UNRECOGNISED]"
            } finally {
                connection.commit()
                connection.close()
            }
        }

        it("does not put query text into the diagnosis, which would carry row data into an error tracker") {
            val connection = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)
            try {
                connection.autoCommit = false
                connection.createStatement().use { it.executeQuery("SELECT 'sensitive-row-value-42'").close() }

                noMargin.describeBlockers() shouldNotContain "sensitive-row-value-42"
            } finally {
                connection.commit()
                connection.close()
            }
        }

        it("says so plainly when nothing is holding it back") {
            noMargin.describeBlockers() shouldContain "no open transactions found"
        }

        it("refuses to report a boundary when pg_stat_activity is hiding backends") {
            // An unprivileged role sees other backends as rows with every column nulled, including the xact_start this
            // mechanism depends on, so min(xact_start) silently collapses to the reader's own transactions. That is a
            // fail-open degradation to the exact bug the boundary exists to prevent, so it must fail loudly instead.
            // Dropped defensively rather than assuming a clean database, since this spec can be pointed at a
            // persistent one. A role holding privileges cannot simply be dropped, so revoke first.
            transaction(db) {
                exec(
                    """
                    DO ${'$'}${'$'}
                    BEGIN
                        IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'boundary_limited') THEN
                            EXECUTE 'REVOKE ALL ON DATABASE ' || quote_ident(current_database()) || ' FROM boundary_limited';
                            EXECUTE 'DROP ROLE boundary_limited';
                        END IF;
                    END
                    ${'$'}${'$'};
                    """.trimIndent(),
                )
                // no GRANT CONNECT needed: PUBLIC holds it by default, and not granting anything keeps the role
                // dependency-free so it can always be dropped again
                exec("CREATE ROLE boundary_limited LOGIN PASSWORD 'limited'")
            }
            val limitedDb = Database.connect(
                url = postgres.jdbcUrl,
                driver = "org.postgresql.Driver",
                user = "boundary_limited",
                password = "limited",
            )

            val exception = shouldThrow<SafeBoundaryUnreliableException> {
                PostgresXactStartSafeBoundary(limitedDb).safeBefore()
            }

            exception.message!! shouldContain "hidden from this connection"
        }
    }
})
