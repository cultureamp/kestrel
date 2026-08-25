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
