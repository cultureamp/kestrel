package com.cultureamp.eventsourcing

import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime

/**
 * Supplies the timestamp below which an [EntitySource]'s rows are safe to read, i.e. the point past which a
 * [BatchedAsyncEntityProcessor] must not advance its bookmark.
 *
 * This exists because an `updated_at` column does not record commit order. Postgres `now()` /
 * `transaction_timestamp()` is fixed when a transaction *starts*, so a transaction that begins at 12:00 and commits at
 * 12:30 makes its rows visible half an hour after the timestamp they carry. A reader that has meanwhile advanced its
 * bookmark past 12:00 — which ordinary concurrent traffic will do for it — never sees those rows again, with no error
 * and no stall to notice. That is the bug this abstraction exists to close.
 *
 * The boundary is **exclusive**: only rows with `updated_at < safeBefore()` may be read. See
 * [PostgresXactStartSafeBoundary] for the implementation that actually makes this safe.
 */
fun interface SafeBoundary {
    fun safeBefore(): Instant

    companion object {
        /**
         * A boundary of "now minus a fixed delay", equivalent to the JDBC source connector's
         * `timestamp.delay.interval.ms`.
         *
         * **This is not safe against long-running transactions**, which is why it is named as it is. It only holds if
         * every transaction that writes the table commits within [delay] of starting, and nothing in the type system or
         * the database enforces that: set it too low and rows are dropped silently. Use it for tests, for databases
         * with no equivalent of `pg_stat_activity`, or where you have independently bounded transaction duration and
         * are choosing this trade knowingly. Otherwise use [PostgresXactStartSafeBoundary].
         */
        fun unsafeFixedDelay(delay: Duration, clock: () -> Instant = Instant::now) = SafeBoundary { clock().minus(delay) }
    }
}

/**
 * Thrown when the boundary cannot be trusted, rather than returning a value that would let the reader skip rows. Both
 * subclasses describe a *configuration* problem, so a processor hitting one will keep hitting it: the intended effect is
 * to wedge the processor loudly (and alert) rather than to run unsafely.
 */
open class SafeBoundaryException(message: String) : RuntimeException(message)

/**
 * Thrown when `pg_stat_activity` is hiding backends from the reader, so `min(xact_start)` is incomplete. See
 * [PostgresXactStartSafeBoundary] for why this is the failure that most needs detecting.
 */
class SafeBoundaryUnreliableException(message: String) : SafeBoundaryException(message)

/**
 * Thrown when the database is configured in a way the boundary cannot reason about.
 */
class SafeBoundaryUnsupportedException(message: String) : SafeBoundaryException(message)

/**
 * The boundary that closes the commit race, using `pg_stat_activity.xact_start` — the same clock reading that `now()`
 * stamps into `updated_at`.
 *
 * The invariant is:
 *
 * > Never advance the reader past the start time of the oldest transaction that could still commit.
 *
 * For a transaction `T` whose rows are not yet visible to the table read:
 *
 * | `T` at the boundary read      | Why its rows cannot be missed                                     |
 * |-------------------------------|-------------------------------------------------------------------|
 * | Open                          | `xact_start(T) >= safeBefore`, so its rows are excluded            |
 * | Starts after the boundary read| `xact_start(T) >` boundary time `>= safeBefore`, so excluded       |
 * | Committed before it           | Absent from `pg_stat_activity`, and visible to the later snapshot  |
 * | Commits just after it         | Was open at the boundary read, so excluded; read on the next poll  |
 *
 * The boundary errs in one direction only: it can withhold rows that were in fact safe to read (they arrive on a later
 * poll), and can never let the bookmark past a row that has not yet appeared.
 *
 * ## The ordering requirement, which is the easy part to get wrong
 *
 * Let `S` be the snapshot the table read uses and `B` the moment `pg_stat_activity` is evaluated. `pg_stat_activity` is
 * not MVCC — it reads shared memory at execution time — so `B` and `S` are independent. A transaction committing in the
 * window `(S, B]` is invisible at `S` *and* already gone from `pg_stat_activity` at `B`: neither visible nor excluded,
 * therefore missed. So:
 *
 * > **Safety requires `B <= S`:** the boundary must be evaluated no later than the snapshot the table is read with.
 *
 * [BatchedAsyncEntityProcessor] gets this by calling [safeBefore] in its own transaction, which commits *before* the
 * source's read transaction starts, so `B < S` holds structurally — independently of isolation level. That matters
 * because the alternatives are all subtly wrong:
 *
 * - **One statement** (CTE, join or subquery combining the two): `S` is taken at statement start, `B` during execution,
 *   so `B > S`. Unsafe.
 * - **Two statements in one `REPEATABLE READ` transaction**: `S` is pinned at the first statement and `B` happens
 *   during it, so `B >= S`. Unsafe — the boundary ends up *fresher* than the data.
 * - **Two statements in one `READ COMMITTED` transaction**: safe, because each statement takes a new snapshot. But it
 *   depends on an isolation level set elsewhere, often in shared connection-pool config by someone with no reason to
 *   know this reader cares, and it fails silently when changed.
 * - **A `VOLATILE` plpgsql function** wrapping both: safe only when the *calling* transaction is `READ COMMITTED`,
 *   since that is what makes the function's statements take fresh snapshots. A function is a round-trip optimisation,
 *   not a correctness mechanism.
 *
 * **Do not merge the boundary read into the source's read transaction to save a round trip.** It looks like a free
 * optimisation and it removes the guarantee.
 *
 * ## Why a margin is still needed
 *
 * A transaction fixes its `transaction_timestamp()` and publishes `xact_start` to shared memory as it starts. There is
 * at least a theoretical window in which a reader observes the latter but not the former, so [margin] is subtracted
 * from the boundary. Note how much narrower this job is than a `timestamp.delay.interval.ms`-style hold-back: it guards
 * a sub-millisecond publication window rather than trying to bound how long a transaction might run, so the default is
 * small and does not need tuning against your workload.
 *
 * ## What it checks, and why
 *
 * `pg_stat_activity` NULLs its columns for backends belonging to roles the reader is not a member of. If that happens,
 * `min(xact_start)` silently collapses to the reader's own transactions and the boundary degrades to "now" — **failing
 * open, in exactly the way the original bug did.** Every call therefore also counts backends whose `backend_type` is
 * null, which is the reliable signal: a row the reader can see always has a `backend_type`, and a redacted one never
 * does. (Checking `state IS NULL` instead does not work — background workers legitimately have a null `state`, so it
 * both false-positives and, because `backend_type` is itself redacted, cannot be combined with
 * `backend_type = 'client backend'`.) Grant `pg_read_all_stats` to the reader, or run every writer under a role the
 * reader is a member of.
 *
 * A prepared transaction (`PREPARE TRANSACTION`) is no longer an ordinary backend in `pg_stat_activity`, so it would be
 * invisible here. `max_prepared_transactions` is therefore asserted to be `0`, its default.
 *
 * ## Liveness
 *
 * The boundary is deliberately not filtered by table: it cannot know which transactions will write the table it
 * guards. So **any** long-running transaction in the same database holds the reader up — a reporting query, a leaked
 * `idle in transaction` connection, a long `ANALYZE`, `CREATE INDEX`. This is the intended trade: the failure mode moves
 * from silent data loss to a visible stall, which [AsyncEntityProcessorMonitor]'s `latencyMs` reports and which is worth
 * alerting on. Setting `idle_in_transaction_session_timeout` bounds the worst case; routing long reporting queries to a
 * replica keeps them out of the primary's `pg_stat_activity` entirely.
 *
 * @param db the database to read the boundary from. Must be the same database the [EntitySource] reads, since
 * `xact_start` values are only comparable within one instance.
 * @param margin subtracted from the boundary, guarding the `xact_start` publication window described above.
 */
class PostgresXactStartSafeBoundary(
    private val db: Database,
    private val margin: Duration = Duration.ofSeconds(1),
) : SafeBoundary {

    override fun safeBefore(): Instant {
        val observation = transaction(db) {
            exec(BOUNDARY_SQL) { rs ->
                if (!rs.next()) throw SafeBoundaryException("Reading the safe boundary returned no row, which pg_stat_activity cannot do")
                Observation(
                    oldestOpenOrNow = rs.getObject(1, OffsetDateTime::class.java).toInstant(),
                    redactedBackends = rs.getInt(2),
                    maxPreparedTransactions = rs.getInt(3),
                )
            }
        } ?: throw SafeBoundaryException("Reading the safe boundary produced no result")

        if (observation.maxPreparedTransactions != 0) {
            throw SafeBoundaryUnsupportedException(
                "max_prepared_transactions is ${observation.maxPreparedTransactions}, but a prepared transaction is not " +
                    "an ordinary backend in pg_stat_activity and so cannot be seen by this boundary. Set it to 0, or " +
                    "position rows by commit order instead.",
            )
        }

        if (observation.redactedBackends != 0) {
            throw SafeBoundaryUnreliableException(
                "${observation.redactedBackends} backend(s) in pg_stat_activity are hidden from this connection, so " +
                    "min(xact_start) is incomplete and rows committed by those backends could be skipped silently. " +
                    "Grant pg_read_all_stats to this role, or run every writer under a role this one is a member of.",
            )
        }

        return observation.oldestOpenOrNow.minus(margin)
    }

    private data class Observation(val oldestOpenOrNow: Instant, val redactedBackends: Int, val maxPreparedTransactions: Int)

    private companion object {
        /**
         * One round trip for all three readings.
         *
         * `min(xact_start)` ignores nulls, so idle backends drop out without needing a predicate. Our own backend is
         * excluded because it cannot be writing the table we are about to read, and including it would peg the boundary
         * to this query's own start time. `clock_timestamp()` is the fallback for "no other transaction is open", and
         * is read from the database so that no application clock enters the comparison — the whole point being that
         * both sides of it are DB-generated.
         *
         * The redaction count deliberately has no `datname` filter: a redacted row has its `datname` nulled too, so
         * filtering by database would hide exactly the rows being counted.
         */
        val BOUNDARY_SQL = """
            SELECT
                COALESCE(
                    min(xact_start) FILTER (WHERE datname = current_database() AND pid <> pg_backend_pid()),
                    clock_timestamp()
                ),
                count(*) FILTER (WHERE backend_type IS NULL),
                current_setting('max_prepared_transactions')::int
            FROM pg_stat_activity
        """.trimIndent()
    }
}
