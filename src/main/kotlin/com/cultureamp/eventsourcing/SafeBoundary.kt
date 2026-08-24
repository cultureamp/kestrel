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

    /**
     * A human-readable account of what is currently holding the boundary back, for logs and error reports. Called only
     * when something has already gone wrong, so it may run an extra query.
     *
     * Deliberately a string rather than structured data: the only consumer is a human reading a Sentry issue or a log
     * line, and keeping it opaque means an implementation can report whatever its database makes available without
     * every caller having to model it.
     */
    fun describeBlockers(): String = "no blocker diagnosis available from this SafeBoundary"

    companion object {
        /**
         * A boundary of "now minus a fixed delay", equivalent to the JDBC source connector's
         * [`timestamp.delay.interval.ms`](https://docs.confluent.io/kafka-connectors/jdbc/current/source-connector/source_config_options.html#mode).
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
 * Thrown when a processor has been unable to make progress for longer than its stall threshold *because* the boundary is
 * holding it back — there are rows in the table it is forbidden to read, and that has been true for too long.
 *
 * This is the loud failure for the one cost the boundary imposes: it cannot distinguish a transaction that will write
 * the table from one that never will, so any long-running transaction in the same database holds the reader up. No rows
 * are lost and the processor recovers on its own once that transaction ends, so this is a staleness alarm rather than a
 * corruption one — but it is thrown rather than logged because "publishing silently stopped hours ago" is precisely the
 * class of problem the boundary exists to eliminate, and it should not be reintroduced at the operational layer.
 *
 * The message carries [SafeBoundary.describeBlockers], so whoever reads the report can see which session to go and
 * close.
 */
class SafeBoundaryStalledException(message: String) : SafeBoundaryException(message)

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
 * ## Why there is no margin
 *
 * Earlier versions subtracted a fixed margin here, to cover the window between a transaction fixing its
 * `transaction_timestamp()` and publishing `xact_start` where a reader might miss it. That margin is gone, because the
 * window can be closed exactly instead of estimated. The property actually required is:
 *
 * > **The boundary must not exceed the instant at which the `pg_stat_activity` snapshot was taken.**
 *
 * Given that, a transaction absent from the snapshot published its `xact_start` *after* the snapshot was taken, and
 * therefore stamps every row it writes later than the boundary. Two facts make it hold:
 *
 * 1. **A transaction cannot write before publishing its `xact_start`.** `pgstat_report_xact_timestamp()` is called
 *    inside `StartTransaction()` (`src/backend/access/transam/xact.c`), which returns before any statement of that
 *    transaction executes — including an implicit single-statement transaction with no client `BEGIN`. So a row's
 *    `clock_timestamp()` stamp is always at or after the `xact_start` that was already visible to readers.
 * 2. **`statement_timestamp()` caps the boundary at the start of this statement.** The `pg_stat_activity` snapshot is
 *    taken during the statement that first reads it, so the snapshot instant is at or after `statement_timestamp()`,
 *    which is at or after the boundary. Combined with 1, any transaction the snapshot missed stamps its rows above the
 *    boundary, and the exclusive comparison drops them.
 *
 * The cap is what makes the fallback case ("no other transaction is open") safe without a clock reading: `LEAST` ignores
 * nulls, so an empty `min(xact_start)` leaves `statement_timestamp()` as the boundary.
 *
 * ### The snapshot must not be stale
 *
 * Fact 2 depends on the snapshot being taken *during* this statement, and that is not automatic:
 * `pgstat_read_current_status()` returns early once `localBackendStatusTable` is populated, and the snapshot is
 * discarded only at transaction end. A `pg_stat_activity` read is therefore cached for the whole reading transaction —
 * a second read in the same transaction can be arbitrarily stale, still listing transactions that have since ended and
 * omitting every transaction that has since begun.
 *
 * A stale snapshot breaks safety in the silent direction: the transactions it lists may all have ended, leaving
 * `min(xact_start)` null and the boundary at `statement_timestamp()`, which is *later* than a transaction that began
 * after the snapshot and has already stamped rows below it. [safeBefore] therefore issues `pg_stat_clear_snapshot()`
 * before reading, so the property holds for any caller rather than only for one that happens to read
 * `pg_stat_activity` exactly once per transaction. It costs a round trip and buys an invariant that would otherwise be
 * a comment.
 *
 * ### The one residual: a backwards step of the system clock
 *
 * `clock_timestamp()` reads `CLOCK_REALTIME`, which is corrected rather than monotonic. If it steps backwards between
 * `StartTransaction` and a write, fact 1 fails and a row can carry a stamp below its own `xact_start`. Stamp
 * `GREATEST(clock_timestamp(), transaction_timestamp())` in the trigger and it cannot: `transaction_timestamp()` *is*
 * the published `xact_start`, read inside the same transaction, so the row is at or after it whatever the clock does.
 * In the ordinary case the `GREATEST` returns `clock_timestamp()` unchanged, so nothing else about the stamping
 * changes.
 *
 * A step after the boundary read remains possible in principle, and requires time sync that slews rather than steps
 * (AWS Time Sync, `chrony` without `makestep`). Note that a fixed margin never addressed this either: daemons slew
 * offsets below their step threshold and step those above it, so any step that actually occurs is larger than the
 * margin that would have covered it.
 *
 * ## What it checks, and why
 *
 * `pg_stat_activity` NULLs columns for backends belonging to roles the reader is not a member of. If that happens,
 * `min(xact_start)` silently collapses to the reader's own transactions and the boundary degrades to "now" — **failing
 * open, in exactly the way the original bug did.** Every call therefore also counts backends attached to this database
 * whose `backend_type` is null, which is the reliable signal: a row the reader can see always has a `backend_type`, and
 * a redacted one never does. (Checking `state IS NULL` instead does not work — background workers legitimately have a
 * null `state`, so it both false-positives and, because `backend_type` is itself redacted, cannot be combined with
 * `backend_type = 'client backend'`.) Redaction leaves `datname` intact, which is what makes scoping the count to this
 * database sound as well as necessary — the boundary SQL's own comment has that argument. Grant `pg_read_all_stats` to
 * the reader, or run every writer under a role the reader is a member of; both work.
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
 */
class PostgresXactStartSafeBoundary(
    private val db: Database,
    private val knownApplicationNames: Set<String> = emptySet(),
) : SafeBoundary {

    /**
     * Names the oldest open transactions in this database, so whoever reads a [SafeBoundaryStalledException] can see
     * which session to close rather than starting from "publishing is behind".
     *
     * Each is tagged `[known]` or `[UNRECOGNISED]` by whether its `application_name` is in [knownApplicationNames].
     * That is the practical answer to "was this our application or somebody with a psql session": a client sets its own
     * `application_name`, so services that set one identify themselves and an interactive session shows up as `psql`,
     * a GUI client's name, or blank. It cannot be authoritative — a name is self-reported and anyone can pass any value
     * — which is exactly why it is confined to this diagnostic and never used to decide the boundary. Excluding a
     * session from `min(xact_start)` on the strength of its name would silently break safety in the case that most
     * warrants care: somebody hand-editing rows in production.
     *
     * Note what is deliberately absent: `pg_stat_activity.query`. For a session idle in transaction that is the last
     * statement it ran, which for a write path is an INSERT or UPDATE carrying row values — customer data, on its way
     * into an error tracker. `pid` plus `application_name` plus a duration is enough to find the session without
     * putting any of it there.
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

    override fun safeBefore(): Instant {
        val observation = transaction(db) {
            // Discard any backend-status snapshot this transaction has already taken, so BOUNDARY_SQL's own read is
            // the one that populates it and the snapshot instant is therefore at or after its statement_timestamp().
            // See "The snapshot must not be stale" above: without this the boundary can be computed from a stale view
            // that omits transactions which have already stamped rows below it.
            exec("SELECT pg_stat_clear_snapshot()")
            exec(BOUNDARY_SQL) { rs ->
                if (!rs.next()) throw SafeBoundaryException("Reading the safe boundary returned no row, which pg_stat_activity cannot do")
                Observation(
                    boundary = rs.getObject(1, OffsetDateTime::class.java).toInstant(),
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
                "${observation.redactedBackends} backend(s) attached to this database are hidden from this connection " +
                    "in pg_stat_activity, so min(xact_start) is incomplete and rows committed by those backends could " +
                    "be skipped silently. Grant pg_read_all_stats to this role, or run every writer under a role this " +
                    "one is a member of.",
            )
        }

        return observation.boundary
    }

    private data class Observation(val boundary: Instant, val redactedBackends: Int, val maxPreparedTransactions: Int)

    private companion object {
        /**
         * One round trip for all three readings.
         *
         * `min(xact_start)` ignores nulls, so idle backends drop out without needing a predicate. Our own backend is
         * excluded from it because it cannot be writing the table we are about to read.
         *
         * `statement_timestamp()` is a *cap*, not a fallback, and it is what removes the need for a margin: no
         * transaction that this statement's snapshot could have missed can have stamped a row before this statement
         * started. `LEAST` ignores nulls, so it doubles as the "no other transaction is open" case without a
         * `COALESCE` — and it is read from the database, so no application clock enters the comparison.
         *
         * Excluding our own backend from `min(xact_start)` is load-bearing, not tidiness. [safeBefore] clears the
         * backend-status snapshot first, so this is the *second* statement of its transaction and our own `xact_start`
         * is strictly earlier than this `statement_timestamp()` — about half a millisecond earlier, measured on
         * Postgres 14. Counting ourselves would therefore pin the boundary to the reader's own poll and it could never
         * advance past it, so an otherwise idle database would sit still. Safety is unaffected either way: what the cap
         * has to satisfy is "no later than the instant the snapshot was taken", and the `statement_timestamp()` of the
         * statement that takes that snapshot is exactly that.
         *
         * The redaction count is scoped to this database, and that scope is exact rather than convenient. Redaction
         * nulls `state`, `xact_start`, `query` and `backend_type`, but **not** `datname`, `usename` or
         * `application_name` — verified against Postgres 14 with a deliberately unprivileged role. So a hidden backend
         * that could matter is still identifiable by database: to write the table being guarded it has to be attached
         * to this one, and to be missed by the boundary it has to already hold a transaction open, which means its
         * `datname` is set. Neither group the scope drops can matter. A redacted row with a null `datname` is an
         * auxiliary process — checkpointer, walwriter, autovacuum launcher — which runs no transaction over a user
         * table; without `pg_read_all_stats` every one of those is redacted, so counting them would leave this check
         * permanently unsatisfiable and the other documented remedy, sharing a role with every writer, unable to work
         * at all. A redacted row in another database cannot write this one. Anything that begins after the snapshot is
         * excluded by the cap instead, visible or not.
         */
        val BOUNDARY_SQL = """
            SELECT
                LEAST(
                    min(xact_start) FILTER (WHERE datname = current_database() AND pid <> pg_backend_pid()),
                    statement_timestamp()
                ),
                count(*) FILTER (WHERE backend_type IS NULL AND datname = current_database()),
                current_setting('max_prepared_transactions')::int
            FROM pg_stat_activity
        """.trimIndent()

        /**
         * The same population the boundary is computed from, listed oldest first. `query` is deliberately not selected:
         * see [describeBlockers].
         */
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
