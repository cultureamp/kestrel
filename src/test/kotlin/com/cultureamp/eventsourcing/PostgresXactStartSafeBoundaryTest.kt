package com.cultureamp.eventsourcing

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.testcontainers.containers.PostgreSQLContainer
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.time.OffsetDateTime

/**
 * Where the Postgres under test comes from. Testcontainers by default, so this runs in CI unattended; an existing
 * database if `KESTREL_TEST_PG_URL` is set, so it can also be run where Docker is unavailable.
 */
private data class PostgresTarget(val jdbcUrl: String, val username: String, val password: String) {
    val databaseName: String get() = jdbcUrl.substringAfterLast('/').substringBefore('?')

    /**
     * The same server, a different database. Used to put a reader somewhere no other client backend is connected, so
     * the only redacted rows it can see are the auxiliary processes.
     */
    fun withDatabase(name: String): PostgresTarget {
        val query = jdbcUrl.substringAfter('?', "").let { if (it.isEmpty()) "" else "?$it" }
        return copy(jdbcUrl = "${jdbcUrl.substringBefore('?').substringBeforeLast('/')}/$name$query")
    }

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

    val safeBoundary = PostgresXactStartSafeBoundary(db)

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

    /**
     * An unprivileged role to read `pg_stat_activity` as, so the redaction cases below see what a reader without
     * `pg_read_all_stats` sees. Dropped and recreated rather than assuming a clean database, since this spec can be
     * pointed at a persistent one; a role holding privileges cannot simply be dropped, so revoke first. Nothing is
     * granted to it — PUBLIC holds CONNECT — which keeps it dependency-free and so always droppable.
     */
    fun recreateLimitedRole() {
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
            exec("CREATE ROLE boundary_limited LOGIN PASSWORD 'limited'")
        }
    }

    fun limitedConnectionTo(target: PostgresTarget): Database = Database.connect(
        url = target.jdbcUrl,
        driver = "org.postgresql.Driver",
        user = "boundary_limited",
        password = "limited",
    )

    describe("safeBefore") {
        it("tracks the database clock when nothing else is open") {
            val before = databaseNow()
            val boundary = safeBoundary.safeBefore()
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
                safeBoundary.safeBefore() shouldBeLessThanOrEqualTo xactStart
            } finally {
                connection.commit()
                connection.close()
            }

            safeBoundary.safeBefore() shouldBeGreaterThanOrEqualTo xactStart
        }

        it("holds the boundary at the oldest open transaction, not the newest") {
            val (oldest, oldestXactStart) = openTransaction()
            val (newer, newerXactStart) = openTransaction()

            try {
                newerXactStart shouldBeGreaterThanOrEqualTo oldestXactStart
                safeBoundary.safeBefore() shouldBeLessThanOrEqualTo oldestXactStart
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
            val first = safeBoundary.safeBefore()
            val second = safeBoundary.safeBefore()

            second shouldBeGreaterThanOrEqualTo first
        }

        it("caps the boundary at the start of its own read, excluding any transaction it could not have seen") {
            // This is the structural replacement for the fixed margin that used to be subtracted here. A transaction
            // beginning after the boundary read may be absent from that read's snapshot of pg_stat_activity; what makes
            // that safe is that its xact_start — and so every row it stamps, since Postgres publishes xact_start in
            // StartTransaction before any statement of the transaction runs — is at or after the boundary returned.
            val boundary = safeBoundary.safeBefore()
            val (connection, xactStart) = openTransaction()

            try {
                xactStart shouldBeGreaterThanOrEqualTo boundary
            } finally {
                connection.commit()
                connection.close()
            }
        }

        it("is not computed from a stale backend-status snapshot") {
            // pg_stat_activity is cached for the whole reading transaction: pgstat_read_current_status() returns early
            // once localBackendStatusTable is populated, and it is discarded only at transaction end. So a caller that
            // has already read pg_stat_activity would otherwise get a boundary derived from a view that omits every
            // transaction begun since — including ones that have already stamped rows below it, which is the silent
            // direction. safeBefore() issues pg_stat_clear_snapshot() for that reason.
            transaction(db) {
                // Populate this transaction's snapshot before the transaction below exists. Exposed joins the nested
                // transaction inside safeBefore() to this one, so the boundary is read with that snapshot in place.
                exec("SELECT count(*) FROM pg_stat_activity") { rs -> rs.next() }

                val (connection, xactStart) = openTransaction()

                try {
                    safeBoundary.safeBefore() shouldBeLessThanOrEqualTo xactStart
                } finally {
                    connection.commit()
                    connection.close()
                }
            }
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

                safeBoundary.describeBlockers() shouldNotContain "sensitive-row-value-42"
            } finally {
                connection.commit()
                connection.close()
            }
        }

        it("says so plainly when nothing is holding it back") {
            safeBoundary.describeBlockers() shouldContain "no open transactions found"
        }

        it("refuses to report a boundary when pg_stat_activity is hiding backends in this database") {
            // An unprivileged role sees other roles' backends with their xact_start nulled, so min(xact_start) silently
            // collapses to the reader's own transactions. That is a fail-open degradation to the exact bug the boundary
            // exists to prevent, so it must fail loudly instead. The transaction held open below is what makes this the
            // dangerous shape rather than merely an under-privileged one: a real transaction, in the reader's own
            // database, that the reader cannot see.
            recreateLimitedRole()
            val limitedDb = limitedConnectionTo(postgres)
            val (connection, _) = openTransaction()

            try {
                // the fail-open being prevented, demonstrated rather than asserted about: to this reader there is no
                // open transaction anywhere, while one demonstrably is.
                transaction(limitedDb) {
                    exec(
                        "SELECT count(*) FROM pg_stat_activity " +
                            "WHERE xact_start IS NOT NULL AND pid <> pg_backend_pid()",
                    ) { rs ->
                        rs.next()
                        rs.getInt(1)
                    }
                } shouldBe 0

                val exception = shouldThrow<SafeBoundaryUnreliableException> {
                    PostgresXactStartSafeBoundary(limitedDb).safeBefore()
                }

                exception.message!! shouldContain "hidden from this connection"
            } finally {
                connection.commit()
                connection.close()
            }
        }

        it("still reports a boundary when the only hidden backends are auxiliary processes") {
            // Redaction hides the auxiliary processes too — checkpointer, walwriter, autovacuum launcher — and there is
            // no privilege short of pg_read_all_stats that reveals them. None of them runs a transaction over a user
            // table, and all of them are attached to no database, which is why the redaction count is scoped to the
            // reader's own. Counting them instead would make the check permanently unsatisfiable and would leave the
            // documented alternative of sharing a role with every writer unable to work at all.
            //
            // Read from a database nothing else is connected to, so the auxiliary processes are the only redacted rows
            // present. Dropped defensively rather than at the end, for the same reason as the role.
            recreateLimitedRole()
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { admin ->
                admin.createStatement().use {
                    it.execute("DROP DATABASE IF EXISTS boundary_isolated")
                    it.execute("CREATE DATABASE boundary_isolated")
                }
            }
            val isolatedDb = limitedConnectionTo(postgres.withDatabase("boundary_isolated"))

            val before = databaseNow()
            val boundary = PostgresXactStartSafeBoundary(isolatedDb).safeBefore()

            // nothing is open in that database, so the boundary is simply the reader's own statement_timestamp()
            boundary shouldBeGreaterThanOrEqualTo before
        }
    }
})
