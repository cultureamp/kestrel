# 0.22.0

## Breaking changes

This release moves makes the updating of `EventsSequenceStats` during `RelationalDatabaseEventStore#sink` optional, 
so that applications can choose to not to this synchronously and update these 
stats asynchronously.

This moves the `#lastSequence(...)` method out of the `EventSource` interface and onto a new
`EventsSequenceStats` class. To migrate, just switch over to calling the method
in its new location.

# 0.25.0

## Breaking changes

The interface for `EventTypeResolver` has changed to support the event-store filtering by `aggregate-type` 
along side `event-type`. This will only affect codebases that provided a custom implementation of this interface.
See `PackageRemovingEventTypeResolver` for an example of how to work with the new interface.

# 0.30.0

## Breaking changes

Exposed has been upgraded from `0.49.0` to `1.3.1`. This is a breaking upgrade for consuming
applications, which will need to make the equivalent changes themselves.

Kestrel is now built with Kotlin `2.2.21`, up from `2.1.0`. Exposed 1.3.1 is published with
Kotlin 2.3 metadata, and a 2.1 compiler cannot read it (`Module was compiled with an
incompatible version of Kotlin`); a 2.2 compiler can, since the compiler reads metadata up to
one minor version ahead. 2.2.21 is therefore the lowest version that can build against Exposed
1.3.1, chosen so that Kestrel imposes no floor of its own beyond the Kotlin `2.2` that Exposed
1.3.1 already requires of anything using it.

Anything testing against H2 also needs H2 `2.x`, as Exposed 1.x rejects H2 1.4 at runtime with
`Unsupported H2 version`.

The stored schema is unchanged, so no data migration is required.

Notable upgrade steps, all of which apply to consuming code too:

- Exposed packages moved from `org.jetbrains.exposed.sql.*` to `org.jetbrains.exposed.v1.*`,
  split into `org.jetbrains.exposed.v1.core` (tables, columns, expressions) and
  `org.jetbrains.exposed.v1.jdbc` (`Database`, `SchemaUtils`, `transaction`, statements).
- `Table.uuid(...)` now maps to `kotlin.uuid.Uuid` rather than `java.util.UUID`. Kestrel's tables
  use `javaUUID(...)` (from `org.jetbrains.exposed.v1.core.java`) to keep the existing
  `java.util.UUID` types and the same `uuid` SQL column type.
- `Table.select { predicate }` is gone: use `selectAll().where { predicate }`. `slice(columns)`
  is replaced by `select(columns)`, optionally followed by `.where { ... }`.
- Comparison operators (`eq`, `neq`, `inList`, `greater`, ...) are now top-level functions in
  `org.jetbrains.exposed.v1.core` rather than `SqlExpressionBuilder` members, and `where { ... }`
  no longer has `SqlExpressionBuilder` as its receiver. Importing
  `org.jetbrains.exposed.v1.core.SqlExpressionBuilder.eq` is now a deprecation error.

The one Kestrel API signature this changes is the lock hook: `blockingLockUntilTransactionEnd`
and `pgAdvisoryXactLock` are now extensions on `JdbcTransaction` rather than `Transaction`,
because `exec` moved to the JDBC-specific transaction type.
# 0.31.0

## Dependencies that appear in the public API are now `api` scope

Kestrel exposes types from several of its dependencies in its own public signatures, but declared
every one of them as `implementation`. Gradle publishes `implementation` as Maven `runtime` scope,
so those types were missing from the compile classpath of anything depending on Kestrel. Consumers
had to redeclare the dependency themselves just to compile against Kestrel's API.

The affected types:

- `Database` (`org.jetbrains.exposed.v1.jdbc`) in `RelationalDatabaseBookmarkStore` and
  `RelationalDatabaseEventStore.create`, and `JdbcTransaction` in `blockingLockUntilTransactionEnd`
  — from `exposed-jdbc`.
- `Table`, `Column` and `ResultRow` in the `Events` table and the `jsonBody` hook — from
  `exposed-core`.
- `ObjectMapper` (`tools.jackson.databind`) in `RelationalDatabaseEventStore.create` and
  `defaultObjectMapper` — reachable through `jackson-module-kotlin`.
- `DateTime` in `Event.createdAt` — from `joda-time`.

These four are now `api`, which the generated POM publishes at `compile` scope. `exposed-jodatime`,
`exposed-json` and `jackson-datatype-joda` stay `implementation`, because only Kestrel's own
internals reference them.

This needs the `java-library` plugin rather than `java`, since the plain `java` plugin has no `api`
configuration.

Nothing is removed or renamed, so this is source and binary compatible. Consumers that already
declare these dependencies can drop them, but nothing breaks if they keep them.

# 0.32.0

## New features

Adds an entity-processor stack, mirroring the event-processor stack for the case where the thing you
want to project from is rows in a table rather than an event stream. Rows are read in `updated_at`
order and tiebroken on a `uuid` id, as the Confluent JDBC source connector does in its
"timestamp+incrementing" mode: see `EntitySource`, `EntityProcessor`, `EntityBookmarkStore`,
`BatchedAsyncEntityProcessor`, `AsyncEntityProcessorMonitor` and `SafeBoundary`, and the
[README](README.md#entity-processors-projecting-from-a-table-rather-than-an-event-stream) for how
they fit together.

Nothing existing changes behaviour. `BookmarkLock` gains a `tryLock(bookmarkName: String)` overload
with a default implementation delegating to the existing `tryLock(bookmark: Bookmark)`, so existing
implementations are unaffected. Entity-bookmark locks are namespaced with an `entity:` prefix, so
they cannot collide with an event-bookmark of the same name — `pg_try_advisory_lock` keys are one
flat space per database, hashed from the name alone. Event-bookmark keys are deliberately left
alone, since changing them would let two instances of a deployed event-processor hold different
locks mid-rollout. The new stack uses `java.time` rather than joda, so it needs
`exposed-java-time` alongside the `exposed-jodatime` the event-sourcing side continues to use.

Two things to know before adopting it.

## Positions are naive timestamps holding UTC

An `EntityPosition` carries a `java.time.LocalDateTime`, read from a `timestamp without time zone`
column and carried around unconverted — the same convention the events table uses for its own
`created_at`. So a position means whatever the column holds, and the convention is that it holds UTC.

Nothing in the library converts between zones, so a column stamped in local time would be compared
against a boundary read in UTC and the hold-back would be wrong by the offset. What the library does
do is make its own defaults consistent: every `clock` parameter defaults to
`LocalDateTime.now(ZoneOffset.UTC)` rather than `LocalDateTime::now`, and
`PostgresXactStartSafeBoundary` converts with an explicit `AT TIME ZONE 'UTC'` rather than leaning on
the session's `TimeZone`, which is connection-pool configuration a reader does not control.

## Reading by `updated_at` needs a safe boundary

That column does not record commit order. Postgres `now()` is fixed when a transaction *starts*, so a
transaction beginning at 12:00 and committing at 12:30 makes rows visible half an hour after the
timestamp they carry, and a reader whose bookmark has meanwhile passed 12:00 never sees those rows
again — with no error and nothing to alert on. A fixed hold-back only makes that unlikely, and only
while every writing transaction commits inside the delay.

`BatchedAsyncEntityProcessor` therefore requires a `SafeBoundary`, and `EntitySource.getAfter` takes
its upper bound as an exclusive `safeBefore`. There is no default, because no one value is safe for
every table. `PostgresXactStartSafeBoundary` closes the race by construction — `min(xact_start)` from
`pg_stat_activity`, capped at `statement_timestamp()` — so the reader never passes the start of the
oldest transaction that could still commit, and no application clock is involved in deciding what is
safe to read.

Its cost is that any long-running transaction in the same database holds the reader up, since the
boundary cannot tell one that will write the polled table from one that never will. That trades
silent data loss for a visible stall: `AsyncEntityProcessorMonitor` reports it as latency, and
`BatchedAsyncEntityProcessor` reports it directly when a poll reads nothing *and* the newest row in
the table sits more than `stallThreshold` (an hour by default) beyond the boundary. Both of those
timestamps come from the database — the head of the table against the oldest open transaction's
`xact_start` — so no application clock is involved and clock skew cannot make a stall appear or
disappear. A processor still reading rows below an old boundary is not stalled and reports nothing,
which keeps a first run over a large table quiet while an unrelated transaction pins the boundary
hours back.

The head of the table is only queried when the boundary is old enough for a stall to be possible.
`SafeBoundary.read()` returns both the boundary and the moment it was read — for
`PostgresXactStartSafeBoundary` both come out of the one query, since `statement_timestamp()` is
already the cap the boundary is computed against — and a row cannot be stamped after the boundary
was read, so `head - safeBefore` can never exceed `readAt - safeBefore`. That makes the cheap
comparison an exact pre-filter rather than a heuristic: at a 100ms poll interval it keeps a query
per poll off an otherwise idle database without ever missing a stall.

`stallBehaviour` decides what a stall does: `StallBehaviour.Throw`, the default, raises
`SafeBoundaryStalledException` naming the pid and `application_name` of the sessions to go and
close; `StallBehaviour.LogAndContinue` reports the same message to a log and keeps polling.

The `SafeBoundary` KDoc carries the rest of the argument, including why the boundary must be read in
its own transaction and why the comparison has to be strict.
