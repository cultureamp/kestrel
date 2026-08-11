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

# Unreleased

## New features

Adds an entity-processor stack, mirroring the event-processor stack for the case where the thing you want to project
from is rows in a table rather than an event stream. Rows are read in `updated_at` order and tiebroken on a `uuid` id,
as the Confluent JDBC source connector does in its "timestamp+incrementing" mode: see `EntitySource`,
`EntityProcessor`, `EntityBookmarkStore`, `BatchedAsyncEntityProcessor` and `AsyncEntityProcessorMonitor`, and the
[README](README.md#entity-processors-projecting-from-a-table-rather-than-an-event-stream) for how they fit together.

This stack uses `java.time.LocalDateTime` rather than joda `DateTime`, so it needs `exposed-java-time` alongside the
`exposed-jodatime` that the event-sourcing side continues to use. An `EntityPosition` is a cursor into a table rather
than a moment in time: it holds whatever a naive `timestamp` column holds, and is only ever compared against other
values from that same column. A zoned type has to be reinterpreted against a time-zone on the way through
`exposed-jodatime`, which leaves `upTo = now - timestampDelayMs` skewed by the JVM's offset from UTC — silently
cancelling the hold-back east of UTC, and stalling reads for hours west of it, while the bookmark round-trip still
looks correct because reads and writes share the mapping. `LocalDateTime` has no zone to reinterpret, and being
nanosecond-precision it round-trips a `timestamp` exactly, so bookmarks no longer need the column to be
millisecond-precision to avoid re-reading a row forever. `EntitySourceStalledException` and
`EntitySourceOrderingException` remain as guards against a source that ignores its `after` predicate.

No breaking changes. `BookmarkLock` gains a `tryLock(bookmarkName: String)` overload, which has a default
implementation delegating to the existing `tryLock(bookmark: Bookmark)`, so existing implementations are unaffected.
