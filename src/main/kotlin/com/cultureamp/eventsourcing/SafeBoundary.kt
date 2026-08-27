package com.cultureamp.eventsourcing

import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * Supplies the timestamp below which an [EntitySource]'s rows are safe to read, i.e. the point past which a
 * [BatchedAsyncEntityProcessor] must not advance its bookmark.
 *
 * It is needed because an `updated_at` column does not record commit order. Postgres `now()` is fixed when a
 * transaction *starts*, so a transaction beginning at 12:00 and committing at 12:30 makes its rows visible half an hour
 * after the timestamp they carry. A reader whose bookmark has meanwhile passed 12:00 — which ordinary concurrent
 * traffic will do for it — never sees those rows again, with no error and nothing to alert on.
 *
 * The boundary is **exclusive**: only rows with `updated_at < safeBefore()` may be read. It is compared directly
 * against the polled column, so it is a UTC [LocalDateTime] like an [EntityPosition], and an implementation reading it
 * from a database must convert explicitly rather than relying on the session time-zone.
 *
 * [PostgresXactStartSafeBoundary] is the implementation to use on Postgres.
 */
fun interface SafeBoundary {
    fun safeBefore(): LocalDateTime

    /**
     * What is currently holding the boundary back, for a log line or an error report. Called only when something has
     * already gone wrong, so it may run an extra query. A string rather than structured data because the only consumer
     * is a human reading it, so an implementation can report whatever its database offers without every caller having
     * to model it.
     */
    fun describeBlockers(): String = "no blocker diagnosis available from this SafeBoundary"

    companion object {
        /**
         * A boundary of "now minus a fixed delay", equivalent to the JDBC source connector's
         * [`timestamp.delay.interval.ms`](https://docs.confluent.io/kafka-connectors/jdbc/current/source-connector/source_config_options.html#mode).
         *
         * **Not safe against long-running transactions**, hence the name: it holds only while every transaction that
         * writes the table commits within [delay], and nothing enforces that — set it too low and rows are dropped
         * silently. Use it for tests, or for a database with no `pg_stat_activity` equivalent.
         *
         * [clock] has to read the same wall-clock that stamps the polled column, so the default reads UTC. The JVM's
         * zone would offset the hold-back, cancelling it east of UTC and stalling reads for hours west of it.
         *
         * Note that this boundary sits exactly [delay] behind now at all times, so a [BatchedAsyncEntityProcessor]
         * using it needs a `stallThreshold` longer than [delay] — otherwise every poll reports a stall.
         */
        fun unsafeFixedDelay(delay: Duration, clock: () -> LocalDateTime = { LocalDateTime.now(ZoneOffset.UTC) }) =
            SafeBoundary { clock().minus(delay) }
    }
}

/**
 * Thrown rather than returning a boundary that would let the reader skip rows. [SafeBoundaryUnreliableException] and
 * [SafeBoundaryUnsupportedException] are both configuration problems, so a processor hitting one keeps hitting it: the
 * intent is to wedge it loudly rather than let it run unsafely.
 */
open class SafeBoundaryException(message: String) : RuntimeException(message)

/** Thrown when `pg_stat_activity` is hiding backends from the reader, leaving `min(xact_start)` incomplete. */
class SafeBoundaryUnreliableException(message: String) : SafeBoundaryException(message)

/** Thrown when the database is configured in a way the boundary cannot reason about. */
class SafeBoundaryUnsupportedException(message: String) : SafeBoundaryException(message)

/**
 * Thrown when the boundary has held a processor back for longer than its stall threshold: there are rows it is
 * forbidden to read, and that has been true for too long. Nothing is lost and it recovers once the blocking transaction
 * ends, so this is a staleness alarm — thrown rather than logged because "publishing stopped hours ago" is the class of
 * problem the boundary exists to remove, and a graph is not where that should be noticed. The message carries
 * [SafeBoundary.describeBlockers].
 */
class SafeBoundaryStalledException(message: String) : SafeBoundaryException(message)

/**
 * Closes the commit race using `pg_stat_activity.xact_start`, which is the same clock reading `now()` stamps into
 * `updated_at`: never advance the reader past the start time of the oldest transaction that could still commit. It errs
 * one way only, withholding rows that were in fact safe to read — they arrive on a later poll — and never admitting a
 * row that has not yet appeared.
 *
 * ## The boundary must be read before the snapshot the rows are read with
 *
 * `pg_stat_activity` is not MVCC; it reads shared memory at execution time. So the moment it is evaluated, `B`, is
 * independent of the snapshot `S` the table is read with, and a transaction committing in `(S, B]` is invisible at `S`
 * and already gone from `pg_stat_activity` at `B` — neither visible nor excluded, therefore missed. Safety needs
 * `B <= S`.
 *
 * [BatchedAsyncEntityProcessor] gets that by calling [safeBefore] in its own transaction, which commits before the
 * source's read transaction starts, so `B < S` holds whatever the isolation level. **Do not merge the two to save a
 * round trip.** Every merged arrangement is either unsafe or accidentally safe: one statement (CTE, join or subquery)
 * evaluates `B` during execution and so after `S`; two statements in one `REPEATABLE READ` transaction pin `S` at the
 * first, leaving the boundary fresher than the data; `READ COMMITTED`, or a `VOLATILE` plpgsql function wrapping both,
 * is safe only by virtue of an isolation level set in connection-pool configuration by someone with no reason to know
 * this reader depends on it.
 *
 * ## Redaction is the failure that most needs detecting
 *
 * `pg_stat_activity` nulls columns for backends belonging to roles the reader is not a member of. `min(xact_start)`
 * then collapses to the reader's own transactions and the boundary degrades to "now" — failing open, into exactly the
 * bug it exists to close. Every call therefore counts hidden backends and throws [SafeBoundaryUnreliableException]
 * rather than reporting a boundary it cannot trust. Grant `pg_read_all_stats` to the reading role.
 * `max_prepared_transactions` is asserted to be 0 for the same reason: a prepared transaction is not an ordinary
 * backend, so it would be invisible here.
 *
 * ## Liveness
 *
 * The boundary cannot know which transactions will write the table it guards, so it is not filtered by table: **any**
 * long-running transaction in the same database holds the reader up — a reporting query, a leaked `idle in transaction`
 * connection, a long `CREATE INDEX`. That is the intended trade, silent data loss for a visible stall, which
 * [AsyncEntityProcessorMonitor]'s `latencyMs` reports and [SafeBoundaryStalledException] escalates. Setting
 * `idle_in_transaction_session_timeout` bounds the worst case; routing long reporting queries to a replica keeps them
 * out of the primary's `pg_stat_activity` entirely.
 *
 * @param db the database to read the boundary from. Must be the one the [EntitySource] reads, since `xact_start` values
 * are only comparable within one instance.
 */
class PostgresXactStartSafeBoundary(
    private val db: Database,
    private val knownApplicationNames: Set<String> = emptySet(),
) : SafeBoundary {

    /**
     * Names the oldest open transactions, so a [SafeBoundaryStalledException] says which session to go and close.
     *
     * Each is tagged `[known]` or `[UNRECOGNISED]` against [knownApplicationNames], which is the practical way to tell
     * one of your own services from somebody's psql session. `application_name` is self-reported, which is exactly why
     * it stays a diagnostic and never reaches the boundary predicate: excluding a session because of its name would
     * break safety in the case that most deserves care, somebody hand-editing rows in production.
     *
     * `pg_stat_activity.query` is deliberately not reported. For a session idle in transaction that is the last
     * statement it ran, which on a write path carries row values — customer data, en route to an error tracker.
     */
    override fun describeBlockers(): String {
        val blockers = transaction(db) {
            exec(BLOCKERS_SQL) { rs ->
                buildList {
                    while (rs.next()) {
                        val applicationName = rs.getString("application_name").orEmpty()
                        val tag = if (applicationName in knownApplicationNames) "known" else "UNRECOGNISED"
                        add(
                            "pid=${rs.getInt("pid")} application_name='$applicationName' [$tag] " +
                                "state='${rs.getString("state").orEmpty()}' " +
                                "openFor=${Duration.ofMillis((rs.getDouble("open_for_seconds") * 1000).toLong())}",
                        )
                    }
                }
            }
        }.orEmpty()

        return if (blockers.isEmpty()) {
            "no open transactions found, so the boundary is not being held back by one now"
        } else {
            "oldest open transactions: ${blockers.joinToString("; ")}"
        }
    }

    override fun safeBefore(): LocalDateTime = observe().validated()

    private fun observe(): Observation {
        return transaction(db) {
            // A pg_stat_activity read is cached for the whole transaction — pgstat_read_current_status() returns early
            // once the snapshot is populated, and it is discarded only at transaction end. Clearing it first means
            // BOUNDARY_SQL's own read populates it, which is what the statement_timestamp() cap below relies on.
            exec("SELECT pg_stat_clear_snapshot()")
            exec(BOUNDARY_SQL) { rs ->
                if (!rs.next()) throw SafeBoundaryException("Reading the safe boundary returned no row, which pg_stat_activity cannot do")
                Observation(
                    boundary = rs.getObject(1, LocalDateTime::class.java),
                    redactedBackends = rs.getInt(2),
                    maxPreparedTransactions = rs.getInt(3),
                )
            }
        } ?: throw SafeBoundaryException("Reading the safe boundary produced no result")
    }

    /**
     * The three readings [BOUNDARY_SQL] returns. Separate from the query so that the two refusals below can be tested
     * without a database configured to provoke them: `max_prepared_transactions` in particular is a server-level
     * setting that cannot be changed per session.
     */
    internal data class Observation(val boundary: LocalDateTime, val redactedBackends: Int, val maxPreparedTransactions: Int) {
        fun validated(): LocalDateTime {
            if (maxPreparedTransactions != 0) {
                throw SafeBoundaryUnsupportedException(
                    "max_prepared_transactions is $maxPreparedTransactions, but a prepared transaction is not " +
                        "an ordinary backend in pg_stat_activity and so cannot be seen by this boundary. Set it to 0, or " +
                        "position rows by commit order instead.",
                )
            }

            if (redactedBackends != 0) {
                throw SafeBoundaryUnreliableException(
                    "$redactedBackends backend(s) attached to this database are hidden from this connection " +
                        "in pg_stat_activity, so min(xact_start) is incomplete and rows committed by those backends could " +
                        "be skipped silently. Grant pg_read_all_stats to this role, or run every writer under a role this " +
                        "one is a member of.",
                )
            }

            return boundary
        }
    }

    private companion object {
        /**
         * One round trip for the boundary, the redaction count and the prepared-transaction setting.
         *
         * `pid <> pg_backend_pid()` excludes our own transaction, which is required rather than tidy: [safeBefore]
         * clears the snapshot first, so this is the *second* statement of its transaction and our own `xact_start`
         * precedes this `statement_timestamp()` — half a millisecond, measured on Postgres 14. Counting ourselves would
         * pin the boundary to the reader's own poll, and an otherwise idle database would never advance.
         *
         * `statement_timestamp()` is a cap, not a fallback. A transaction this statement's snapshot could have missed
         * published its `xact_start` after the snapshot was taken, and Postgres publishes that inside
         * `StartTransaction()` before any statement of the transaction runs, so every row it writes is stamped above
         * the cap. (Unless the system clock steps backwards mid-transaction,
         * which is why the README recommends stamping `GREATEST(clock_timestamp(), transaction_timestamp())`.) `LEAST`
         * ignores nulls, so the cap doubles as the "nothing else is open" case, and no application clock is involved.
         *
         * `AT TIME ZONE 'UTC'` renders the boundary as the naive UTC timestamp the polled column holds. Spelled out
         * rather than left to the session's `TimeZone`, which is connection-pool configuration this reader does not
         * control.
         *
         * The redaction count keys on `backend_type IS NULL`, since a visible row always has one and a redacted row
         * never does; `state IS NULL` would false-positive on background workers. Scoping it to this database is exact
         * rather than convenient: redaction leaves `datname` intact, a backend has to be attached to this database to
         * write the guarded table, and one already holding a transaction open has a `datname` by definition. What the
         * scope drops is auxiliary processes — checkpointer, walwriter, autovacuum launcher — which report no database
         * and run no transaction over a user table. They are redacted from anyone without `pg_read_all_stats`, so
         * counting them would leave this check permanently unsatisfiable.
         */
        val BOUNDARY_SQL = """
            SELECT
                LEAST(
                    min(xact_start) FILTER (WHERE datname = current_database() AND pid <> pg_backend_pid()),
                    statement_timestamp()
                ) AT TIME ZONE 'UTC',
                count(*) FILTER (WHERE backend_type IS NULL AND datname = current_database()),
                current_setting('max_prepared_transactions')::int
            FROM pg_stat_activity
        """.trimIndent()

        /** The same population the boundary is computed from, oldest first. `query` is not selected: see [describeBlockers]. */
        val BLOCKERS_SQL = """
            SELECT
                pid,
                application_name,
                state,
                extract(epoch FROM (clock_timestamp() - xact_start)) AS open_for_seconds
            FROM pg_stat_activity
            WHERE datname = current_database()
              AND xact_start IS NOT NULL
              AND pid <> pg_backend_pid()
            ORDER BY xact_start ASC
            LIMIT 5
        """.trimIndent()
    }
}
