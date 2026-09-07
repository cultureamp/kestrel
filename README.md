<img src="logo/png/Kestrel-Logo_FA-OL-1Kpx.png" width="425" alt="">

# Kestrel (Kotlin Event-Sourcing)

A framework for building event-sourced, CQRS applications in Kotlin.

## Summary

Event-sourcing is an architectural paradigm wherein application state is modelled and stored as an immutable sequence of semantic events which are meaningful in your application's domain.

CQRS, Command/Query Responsibility Segregation, describes a pattern in which 
write (command) actions and read (query) actions are codified in entirely separately classes, models and pathways 
through your system. 

Used in tandem, event-sourcing and CQRS provide a powerful and flexible architectural pattern. In
an event-sourced, CQRS system, writes typically happen via an event-centric domain model, also known as "Aggregates", and 
these changes propagate through to "projections" of those events to be read from by the view side of the application.
Events are thus considered the source of truth, while projections are disposable and can be rebuilt by reprocessing the historical events.

**Kes**trel is a **K**otlin **E**vent-**S**ourcing and CQRS framework that strives for:
- Minimalism - *lack of boilerplate*
- Expressiveness - *expressing domain rules well*
- Robustness - *help you not make mistakes, primarily through strong typing*

Here's an example of how an Aggregate might look in Kestrel:
```kotlin
data class SurveyAggregate(val name: Map<Locale, String>, val accountId: UUID, val deleted: Boolean = false) {
    constructor(event: Created): this(event.name, event.accountId)

    companion object {
        fun create(query: SurveyNamesQuery, command: SurveyCreationCommand): Either<SurveyError, Created> = when (command) {
            is CreateSurvey -> when {
                command.name.any { (locale, name) -> query.nameExistsFor(command.accountId, name, locale)} -> Left(SurveyNameNotUnique)
                else -> Right(Created(command.name, command.accountId, command.createdAt))
            }
        }
    }

    fun updated(event: SurveyUpdateEvent): SurveyAggregate = when (event) {
        is Renamed -> this.copy(name = name + (event.locale to event.name))
        is Deleted -> this.copy(deleted = true)
        is Restored -> this.copy(deleted = false)
    }

    fun update(query: SurveyNamesQuery, command: SurveyUpdateCommand): Either<SurveyError, List<SurveyUpdateEvent>> = when (command) {
        is Rename -> when {
            name.get(command.locale) == command.newName -> Left(AlreadyRenamed)
            query.nameExistsFor(accountId, command.newName, command.locale) -> Left(SurveyNameNotUnique)
            else -> Right.list(Renamed(command.newName, command.locale, command.renamedAt))
        }
        is Delete -> when (deleted) {
            true -> Left(AlreadyDeleted)
            false -> Right.list(Deleted(command.deletedAt))
        }
        is Restore -> when (deleted) {
            true -> Right.list(Restored(command.restoredAt))
            false -> Left(NotDeleted)
        }
    }
}

sealed class SurveyCommand : Command
sealed class SurveyCreationCommand : SurveyCommand(), CreationCommand
data class CreateSurvey(override val aggregateId: UUID, val surveyCaptureLayoutAggregateId: UUID, val name: Map<Locale, String>, val accountId: UUID, val createdAt: DateTime) : SurveyCreationCommand()
sealed class SurveyUpdateCommand : SurveyCommand(), UpdateCommand
data class Rename(override val aggregateId: UUID, val newName: String, val locale: Locale, val renamedAt: DateTime) : SurveyUpdateCommand()
data class Delete(override val aggregateId: UUID, val deletedAt: DateTime) : SurveyUpdateCommand()
data class Restore(override val aggregateId: UUID, val restoredAt: DateTime) : SurveyUpdateCommand()

sealed class SurveyEvent : DomainEvent
data class Created(val name: Map<Locale, String>, val accountId: UUID, val createdAt: DateTime) : SurveyEvent(), CreationEvent
sealed class SurveyUpdateEvent : SurveyEvent(), UpdateEvent
data class Renamed(val name: String, val locale: Locale, val namedAt: DateTime) : SurveyUpdateEvent()
data class Deleted(val deletedAt: DateTime) : SurveyUpdateEvent()
data class Restored(val restoredAt: DateTime) : SurveyUpdateEvent()

sealed class SurveyError : DomainError
object SurveyNameNotUnique : SurveyError()
object AlreadyRenamed : SurveyError(), AlreadyActionedCommandError
object AlreadyDeleted : SurveyError(), AlreadyActionedCommandError
object NotDeleted : SurveyError(), AlreadyActionedCommandError

enum class Locale {
    en, de
}

```

### Glossary

- [**Aggregate**](https://github.com/cultureamp/kotlin-eventsourcing/blob/master/src/main/kotlin/com/cultureamp/eventsourcing/Aggregate.kt) -
*The domain entity that  commands interact with and to which events happen. All events happen to a "thing" and this is that
thing, a context in which to group events. A system will often have multiple aggregates.*
- [**Command**](https://github.com/cultureamp/kotlin-eventsourcing/blob/master/src/main/kotlin/com/cultureamp/eventsourcing/Framework.kt) -
*A request to change the system via an event on an aggregate. May be accepted or denied based on business rules.*
- [**Event**](https://github.com/cultureamp/kotlin-eventsourcing/blob/master/src/main/kotlin/com/cultureamp/eventsourcing/Framework.kt) -
*A "semantic" domain event that has happened. Events can't be undone once they have happened, and can't be
blocked like commands. Events exist in an immutable event stream and once they exist need to be supported forever. At an 
implementation detail an `Event` is a wrapper around a `DomainEvent` with additional metadata attached.*
- [**CommandGateway**](https://github.com/cultureamp/kotlin-eventsourcing/blob/master/src/main/kotlin/com/cultureamp/eventsourcing/CommandGateway.kt) -
*The interface through which commands make their way through to aggregates. It's responsible for routing commands
to aggregates, and orchestrates the loading and saving of aggregates through events and the `EventStore`.*
- [**EventStore**](https://github.com/cultureamp/kotlin-eventsourcing/blob/master/src/main/kotlin/com/cultureamp/eventsourcing/EventStore.kt) -
*Implements two interfaces, an `EventSink`, for saving events for aggregates, and an `EventSource`, for retrieving those
events. In general, the event store should only ever be written to via the `CommandGateway` and read from via an 
`EventProcessor`. Kestrel provides support for a postgres backed event store out of the box.*
- [**EventProcessor**](https://github.com/cultureamp/kotlin-eventsourcing/blob/master/src/main/kotlin/com/cultureamp/eventsourcing/EventProcessor.kt) -
*Provides an abstraction over any event-processor, for example a `Projector` or a `Reactor`. This takes care of filtering
out any irrelevant events from being passed to said projectors or reactors.*
- **Projector** - *An event processor that merely updates a "projection" of the data. Should always be disposable and 
re-runnable from the beginning of the event-sequence. Should be built in an [idempotent](https://en.wikipedia.org/wiki/Idempotence)
fashion since event-sourced systems favour asynchronous, distributed systems where it becomes more and more impossible
to create perfect transactions. Build these as if they are an at-least-once delivery of events.*
- **Reactor** - *Like a projector but has side effects, for example sending `Commands` to `Aggregates` via the 
`CommandGateway`, or sending emails, etc. Best efforts should also be made to make these idempotent and re-runnable from
the beginning of the event-sequence, although in practice this tends to be difficult.*
- [**AsyncEventProcessor**](https://github.com/cultureamp/kotlin-eventsourcing/blob/master/src/main/kotlin/com/cultureamp/eventsourcing/BatchedAsyncEventProcessor.kt) -
*Wraps an `EventProcessor` with logic to read events from an `EventSource`, dispatch events to the `EventProcessor`, and
update a "bookmark" representing the sequence number of the last processed event in a `BookmarkStore`.*
- [**BookmarkStore**](https://github.com/cultureamp/kotlin-eventsourcing/blob/master/src/main/kotlin/com/cultureamp/eventsourcing/BookmarkStore.kt) -
*Stores the last processed sequence number as a bookmark for a given `EventProcessor`.*
- [**AsyncEventProcessorMonitor**](https://github.com/cultureamp/kotlin-eventsourcing/blob/master/src/main/kotlin/com/cultureamp/eventsourcing/AsyncEventProcessorMonitor.kt) -
*Provides a mechanism to establish how far `EventProcessor` bookmarks/processing is lagging behind the head of the event
stream.*
- [**EntitySource**](https://github.com/cultureamp/kotlin-eventsourcing/blob/master/src/main/kotlin/com/cultureamp/eventsourcing/EntitySource.kt) -
*Like an `EventSource`, but reads rows from a table in `updated_at` order (tiebreaking on the row's `uuid` id) rather
than events in sequence order. Along with `EntityProcessor`, `EntityBookmarkStore` and `BatchedAsyncEntityProcessor`,
this lets you build a projector over a table you don't own. See
[Entity-processors](#entity-processors-projecting-from-a-table-rather-than-an-event-stream).*

## Getting Started

### Sample App

See the [sample app](https://github.com/cultureamp/kotlin-es-sample-service) for an example of creating a web-app built
on top of this framework.

### Adding as a dependency

Gradle:

```
dependencies {
    implementation "com.cultureamp:kestrel:{kestrel_version}"
}
```

## Usage

### Aggregates

KES offers multiple ways of defining your aggregates depending on your needs.

#### Using interfaces

The simplest way to get started is to use the [`SimpleAggregate[Constructor]`](/src/main/kotlin/com/cultureamp/eventsourcing/Aggregate.kt) 
interface. If you are not sure which aggregate creation method to use, we recommend this option.
For example:

```kotlin
data class SimpleThingAggregate(val tweaks: List<String> = emptyList(), val boops: List<Booped> = emptyList()) : 
    SimpleAggregate<SimpleThingUpdateCommand, SimpleThingUpdateEvent> {
    companion object : 
        SimpleAggregateConstructor<SimpleThingCreationCommand, SimpleThingCreationEvent, SimpleThingUpdateCommand, SimpleThingUpdateEvent> {
        override fun created(event: SimpleThingCreationEvent) = when(event) {
            is SimpleThingCreated -> SimpleThingAggregate()
        }

        override fun create(command: SimpleThingCreationCommand) = when(command){
            is CreateSimpleThing -> Right(SimpleThingCreated)
        }
    }

    override fun updated(event: SimpleThingUpdateEvent) = when(event){
        is Twerked -> this.copy(tweaks = tweaks + event.tweak)
        is Booped -> this.copy(boops = boops + event)
    }

    override fun update(command: SimpleThingUpdateCommand) = when(command) {
        is Twerk -> Right.list(Twerked(command.tweak))
        is Boop -> Right.list(Booped)
        is Bang -> Left(Banged)
    }
}
```

This can then by wired into your [`CommandGateway`](/src/main/kotlin/com/cultureamp/eventsourcing/CommandGateway.kt) 
like so:

```kotlin
val commandGateway = CommandGateway(eventStore, Route.from(SimpleThingAggregate))
```

If you need access to dependencies during command-handling, or want finer grained control over returned error types or
"self" types, there are a few different interfaces you can use:
* [`SimpleAggregate[Constructor]WithProjection`](/src/main/kotlin/com/cultureamp/eventsourcing/Aggregate.kt) as above 
but with access to a dependency during command-handling.
* [`Aggregate[Constructor]`](/src/main/kotlin/com/cultureamp/eventsourcing/Aggregate.kt) explicit error and self types.
* [`Aggregate[Constructor]WithProjection`](/src/main/kotlin/com/cultureamp/eventsourcing/Aggregate.kt) as above but with 
access to a dependency during command-handling.

#### Using functions

If you prefer, you can also model your aggregates in a more functional-programming style using a group of related 
functions. This is useful for when you want more control over how you write your aggregates, for example to utilize the 
constructor of your class (not possible via interfaces), to only inject a dependency into one of the two 
command-handling methods, when you don't want to clutter your domain code with loads of generics but still want rich 
types, or if you simply just prefer thinking in functions.

Here's an example:

```kotlin
data class SurveyAggregate(val name: Map<Locale, String>, val accountId: UUID, val deleted: Boolean = false) {
    constructor(event: Created): this(event.name, event.accountId)

    companion object {
        fun create(query: SurveyNamesQuery, command: SurveyCreationCommand): Either<SurveyError, Created> = when (command) {
            is CreateSurvey -> when {
                command.name.any { (locale, name) -> query.nameExistsFor(command.accountId, name, locale)} -> Left(SurveyNameNotUnique)
                else -> Right(Created(command.name, command.accountId, command.createdAt))
            }
        }
    }

    fun updated(event: SurveyUpdateEvent): SurveyAggregate = when (event) {
        is Renamed -> this.copy(name = name + (event.locale to event.name))
        is Deleted -> this.copy(deleted = true)
        is Restored -> this.copy(deleted = false)
    }

    fun update(query: SurveyNamesQuery, command: SurveyUpdateCommand): Either<SurveyError, List<SurveyUpdateEvent>> = when (command) {
        is Rename -> when {
            name.get(command.locale) == command.newName -> Left(AlreadyRenamed)
            query.nameExistsFor(accountId, command.newName, command.locale) -> Left(SurveyNameNotUnique)
            else -> Right.list(Renamed(command.newName, command.locale, command.renamedAt))
        }
        is Delete -> when (deleted) {
            true -> Left(AlreadyDeleted)
            false -> Right.list(Deleted(command.deletedAt))
        }
        is Restore -> when (deleted) {
            true -> Right.list(Restored(command.restoredAt))
            false -> Left(NotDeleted)
        }
    }
}
```

This can then by wired into your [`CommandGateway`](/src/main/kotlin/com/cultureamp/eventsourcing/CommandGateway.kt) 
like so:

```kotlin
val commandGateway = CommandGateway(
    eventStore,
    Route.from(
        SurveyAggregate.Companion::create.partial(SurveyNameAlwaysAvailable),
        SurveyAggregate::update.partial2(SurveyNameAlwaysAvailable),
        ::SurveyAggregate,
        SurveyAggregate::updated
    )
)
```

If you happen to have a "stateless" aggregate that doesn't need to update its internal state to handle commands, you
can model that too:

```kotlin
object PaymentSagaAggregate {
    fun create(command: StartPaymentSaga): Either<DomainError, PaymentSagaStarted> = with(command) {
        Right(PaymentSagaStarted(fromUserId, toUserBankDetails, dollarAmount, DateTime()))
    }

    fun update(command: PaymentSagaUpdateCommand): Either<DomainError, List<PaymentSagaUpdateEvent>> = when (command) {
        is StartThirdPartyPayment -> Right.list(StartedThirdPartyPayment(command.startedAt))
        is RegisterThirdPartySuccess -> Right.list(FinishedThirdPartyPayment(DateTime()))
        is RegisterThirdPartyFailure -> Right.list(FailedThirdPartyPayment(DateTime()))
        is StartThirdPartyEmailNotification -> Right.list(StartedThirdPartyEmailNotification(command.message, command.startedAt))
    }
}
```

```kotlin
val gateway = CommandGateway(
    eventStore,
    Route.fromStateless(
        PaymentSagaAggregate::create,
        PaymentSagaAggregate::update,
        PaymentSagaAggregate
    )
)
```

### Event-processors (Projectors and Reactors)

Kestrel offers multiple ways of defining your event-processors depending on your needs.

#### Using interfaces

The simplest way to get started is to use the [`DomainEventProcessor`](/src/main/kotlin/com/cultureamp/eventsourcing/DomainEventProcessor.kt) 
interface. If you don't think you'll need access to the event metadata, we recommend this option.
For example:

```kotlin
class SurveyNamesCommandProjector(private val database: Database): DomainEventProcessor<SurveyEvent> {
    override fun process(event: SurveyEvent, aggregateId: UUID): Unit = transaction(database) {
        when (event) {
            is Created -> event.name.forEach { locale, name ->
                SurveyNames.insert {
                    it[surveyId] = aggregateId
                    it[accountId] = event.accountId
                    it[SurveyNames.locale] = locale
                    it[SurveyNames.name] = name
                }
            }
            is Renamed ->
                SurveyNames.update({ SurveyNames.surveyId eq aggregateId }) {
                    it[locale] = event.locale
                    it[name] = event.name
                }
            is Deleted ->
                SurveyNames.deleteWhere { SurveyNames.surveyId eq aggregateId }
            is Restored -> Unit
        }
    }

    init {
        transaction(database) {
            SchemaUtils.create(SurveyNames)
        }
    }
}

object SurveyNames : Table() {
    val surveyId = uuid("survey_id")
    val accountId = uuid("account_id")
    val locale = enumerationByName("locale",  10, Locale::class)
    val name = text("name").index()
}
```

This can then by wired into your application like so:

```kotlin
val projector = SurveyNamesCommandProjector(database)
val bookmarkName = "SurveyNames"
val eventProcessor = EventProcessor.from(projector)
```

If you want to process aynchronously you can do something like:

```kotlin
val asyncEventProcessor = BatchedAsyncEventProcessor(eventStore, bookmarkStore, bookmarkName, eventProcessor)
thread(start = true, isDaemon = false, name = asyncEventProcessor.bookmarkName) {
    ExponentialBackoff(
        onFailure = { e, _ -> println(e) }
    ).run {
        asyncEventProcessor.processOneBatch()
    }
}
```

Or, if you must, you can run it synchronously like:

```kotlin
val eventStore = RelationalDatabaseEventStore.create(listOf(eventProcessor), database)
```

If you need access to the event metadata during handling, you can use the slightly more verbose interface
[`DomainEventProcessor`](/src/main/kotlin/com/cultureamp/eventsourcing/DomainEventProcessor.kt)

#### Using functions

If you prefer, you can also write your event-processor in an interface agnostic way. This is useful for when you want 
more control over how you write your event-processors, for example writing a single class that handles two or more
unrelated domain event types (not possible via interfaces), or if you just don't like interfaces.
For example:

```kotlin
class AnimalProjector(val database: Database) {

    fun first(event: CatAggregateEvent) = transaction(database) {
        when (event) {
            is CatNamed -> {
                AnimalNames.insert {
                    it[name] = event.name
                    it[type] = "cat"
                }
            }
            is CatFed -> Unit
        }
    }
    
    fun second(event: DogAggregateEvent) = transaction(database) {
        when (event) {
            is DogNamed -> {
                AnimalNames.insert {
                    it[name] = event.name
                    it[type] = "dog"
                }
            }
            is DogBarked -> Unit
        }
    }
}
```

This can then by wired into your application like so:

```kotlin
val animalProjector = AnimalProjector(database)
val eventProcessor = EventProcessor.compose(
   EventProcessor.from(animalProjector::first),
   EventProcessor.from(animalProjector::second)
)
val bookmarkName = "AnimalNames"
val asyncEventProcessor = BatchedAsyncEventProcessor(eventStore, bookmarkStore, bookmarkName, eventProcessor)
```

Using `EventProcessor#compose` allows one to wrap up the two event-handling methods as one `EventProcessor` which then
allows the sharing of a single bookmark.

### Event processor monitor

When running `AsyncEventProcessors`, it becomes important to be able to monitor where each of these are up to in the 
event stream. You can do this using the [AsyncEventProcessorMonitor](https://github.com/cultureamp/kotlin-eventsourcing/blob/master/src/main/kotlin/com/cultureamp/eventsourcing/AsyncEventProcessorMonitor.kt)

```kotlin
val asyncEventProcessors: List<AsyncEventProcessor> = ...
thread(start = true, isDaemon = false, name = "eventProcessorMonitor") {
    val eventProcessorMonitor = AsyncEventProcessorMonitor(asynchronousEventProcessors) {
        println("msg='Lag calculation for event-processor' name='${it.name}' lag=${it.lag} bookmarkSequence=${it.bookmarkSequence} lastSequence=${it.lastSequence}")
    }

    ExponentialBackoff(
        idleTimeMs = 60_000,
        failureBackoffMs = { 60_000 },
        onFailure = { throwable, _ -> println(throwable) }
    ).run {
        eventProcessorMonitor.run()
        Action.Wait
    }
}
```

### Entity-processors (projecting from a table rather than an event stream)

Sometimes the data you want to project isn't an event stream at all — it's rows in a table owned by some other system.
Kestrel mirrors the whole event-processor stack for that case, reading rows in `updated_at` order and tiebreaking on a
`uuid` id, the same way the
[Confluent JDBC source connector](https://docs.confluent.io/kafka-connectors/jdbc/current/overview.html) does in its
"timestamp+incrementing" mode. Each event-sourcing piece has an entity equivalent:

| Event stream               | Entity table                       | Notes                                                        |
|----------------------------|------------------------------------|--------------------------------------------------------------|
| `sequence: Long`           | `EntityPosition(updatedAt, id)`    | Total ordering, with the id as the tiebreaker. A null position is the equivalent of sequence `0` |
| `SequencedEvent`           | `PositionedEntity`                 |                                                              |
| `EventSource`              | `EntitySource`                     | A repository call: "give me rows after this position"         |
| `EventProcessor`           | `EntityProcessor`                  |                                                              |
| `BookmarkStore`            | `EntityBookmarkStore`              | Bookmarks store `(entity_last_updated_at, entity_last_id)` instead of a sequence |
| `BatchedAsyncEventProcessor` | `BatchedAsyncEntityProcessor`    |                                                              |
| `EventsSequenceStats`      | `EntityUpdatedAtStats`             | The head of the stream, i.e. the newest `updated_at`         |
| `AsyncEventProcessorMonitor` | `AsyncEntityProcessorMonitor`    | Reports lag in milliseconds rather than in sequence numbers   |
| `Lag`                      | `EntityLag`                        |                                                              |

The source can be any function that takes a position, an exclusive upper bound on `updated_at`, and a batch size:

```kotlin
val entitySource = EntitySource.from { after, safeBefore, batchSize -> goalRelationshipRepository.updatedAfter(after, safeBefore, batchSize) }
```

Writing one by hand means honouring the contract documented on `EntitySource`: return only rows strictly after `after`
and strictly before `safeBefore`, in ascending `(updated_at, id)` order. A source that returns a row its bookmark is
already on, or returns rows out of order, would silently skip rows or reprocess one forever, so
`BatchedAsyncEntityProcessor` checks each row as it goes and throws `EntitySourceStalledException` or
`EntitySourceOrderingException` instead.

Or, for an [Exposed](https://github.com/JetBrains/Exposed) table, use the provided implementation, which doubles as the
`EntityUpdatedAtStats` used for lag monitoring:

```kotlin
val entitySource = RelationalDatabaseEntitySource(
    db = database,
    table = GoalRelationships,
    updatedAtColumn = GoalRelationships.updatedAt,
    idColumn = GoalRelationships.id,
    rowToEntity = { GoalRelationship(it[GoalRelationships.id], it[GoalRelationships.childGoalId], it[GoalRelationships.updatedAt]) },
)
```

The polled column doesn't have to be called `updated_at`; it just has to move forward every time a row changes. See
[choosing the polled column](#things-to-watch-out-for) below, because getting this wrong silently misses updates. Map it
in your table with Kestrel's `utcDatetime("updated_at")` rather than Exposed's `datetime`, for the reason given under
[positions and time zones](#things-to-watch-out-for); Kestrel reads and binds positions zone-free whatever the mapping,
so `datetime` is not wrong for positions, but `rowToEntity` reading the column would still go through Exposed's type.

Wiring it up then looks just like an `AsyncEventProcessor`:

```kotlin
val bookmarkStore = RelationalDatabaseEntityBookmarkStore(database).also { it.createSchemaIfNotExists() }
val asyncEntityProcessor = BatchedAsyncEntityProcessor(
    entitySource = entitySource,
    entityUpdatedAtStats = entitySource,
    bookmarkStore = bookmarkStore,
    bookmarkName = "GoalRelationshipNames",
    entityProcessor = EntityProcessor.from(goalRelationshipProjector::project),
    safeBoundary = PostgresXactStartSafeBoundary(database),
)
thread(start = true, isDaemon = false, name = asyncEntityProcessor.bookmarkName) {
    ExponentialBackoff(onFailure = { e, _ -> println(e) }).run {
        asyncEntityProcessor.processOneBatch()
    }
}
```

And monitoring works the same way, with lag expressed in milliseconds:

```kotlin
val entityProcessorMonitor = AsyncEntityProcessorMonitor(asyncEntityProcessors) {
    println("msg='Lag calculation for entity-processor' name='${it.name}' lagMs=${it.lagMs} latencyMs=${it.latencyMs}")
}
```

`EntityLag` exposes two numbers, because they fail differently: `lagMs` is how far the bookmark is behind the newest row
in the table, and `latencyMs` is how stale the bookmark is in wall-clock terms. Prefer alerting on `latencyMs`, since it
keeps growing when a processor is stuck even if nothing new is being written.

#### Maintaining the polled column

Kestrel only ever reads the polled column. Whatever maintains it is yours, and the boundary's correctness depends on
it, so it is worth getting right in one go. On Postgres:

```sql
CREATE FUNCTION set_updated_at_clock_utc() RETURNS trigger LANGUAGE plpgsql AS $$
  BEGIN
    NEW.updated_at = GREATEST(
      clock_timestamp() AT TIME ZONE 'UTC',
      transaction_timestamp() AT TIME ZONE 'UTC',
      (SELECT max(updated_at) FROM goal_relationships) + interval '1 microsecond'
    );
    RETURN NEW;
  END;
$$;

CREATE TRIGGER set_goal_relationships_updated_at BEFORE INSERT OR UPDATE
  ON goal_relationships FOR EACH ROW EXECUTE PROCEDURE set_updated_at_clock_utc();

CREATE INDEX goal_relationships_updated_at_id ON goal_relationships (updated_at, id);
```

Each part of that prevents a specific failure:

- **`clock_timestamp()`, not `now()`.** `now()` is `transaction_timestamp()`, fixed when the transaction starts, so a
  transaction that began earlier but writes later stamps a *smaller* value than the row already had. The column then
  moves backwards and a row that has already been read is missed. `clock_timestamp()` reads the wall clock at the
  moment of the write, and because a `BEFORE` trigger runs after the row lock is taken, two transactions updating one
  row are serialised and the later writer necessarily reads a later clock. It also matters for a table whose rows are
  never updated: the boundary's cap is the moment its own statement began, and a transaction that took its `now()`
  just before that but was descheduled before publishing itself is stamped below the cap by `now()`, and above it by
  `clock_timestamp()`, which cannot read earlier than the write itself.
- **`GREATEST(..., transaction_timestamp())`.** The boundary is the oldest open transaction's `xact_start`, and
  safety needs every row stamped at or after *its own* `xact_start`. `transaction_timestamp()` is exactly that value,
  so the `GREATEST` guarantees it even if the system clock steps backwards mid-transaction — `clock_timestamp()` reads
  `CLOCK_REALTIME`, which is corrected rather than monotonic. In the ordinary case it returns `clock_timestamp()`
  unchanged.
- **`GREATEST(..., max(updated_at) + 1 microsecond)`.** The two clock readings above agree with each other, but a
  clock stepped *backwards between* transactions agrees with nothing: the bookmark holds a stamp from before the step,
  and every transaction after it would stamp and commit below that bookmark for as long as the step lasts, with the
  boundary following the new clock and nothing to alert on. Every bookmark is a committed row's stamp, so it is at or
  below the table's maximum; stamping each row strictly above that maximum keeps new rows above every bookmark
  whatever the clock says. During a step the stamps count up from the old maximum a microsecond per write until the
  clock catches up, and the processor, held below the clock by the boundary, reads nothing until the clock passes the
  bookmark — a pause of the step's length, not a loss, and one `clockStepLog` reports. Without this floor those rows
  are lost silently, and Kestrel cannot tell, since nothing below the bookmark is ever selected. The subquery is one
  probe of the `(updated_at, id)` index per written row, a few microseconds; it needs the full index, not a partial
  one matching a `filter`, since the floor must cover every row. The trade is that during a step the column reads as
  a time up to the step's length in the future. On an empty table `max()` is null and `GREATEST` ignores it.
- **`AT TIME ZONE 'UTC'` on each clock reading, into a `timestamp without time zone`.** Positions are naive UTC and
  Kestrel converts the boundary the same explicit way, so neither side depends on the session's `TimeZone`. It goes
  on each clock reading rather than around the `GREATEST`, because `max(updated_at)` is already naive and mixing it
  with `timestamptz` values would coerce it through the session zone.
- **`BEFORE INSERT OR UPDATE`, assigning unconditionally, with no column default.** The application must not be able
  to supply the value, or it can supply one below the boundary or below the row's previous value. Note that an ORM may
  send a value whether you want it to or not — Exposed needs a `clientDefault` on a non-nullable column to permit a
  `batchInsert` — and an unconditional trigger is what makes that harmless. A trigger guarded with
  `WHEN (NEW.updated_at IS NULL)` would let the application's clock win.
- **An index on `(updated_at, id)`**, which is the order rows are read in. If you pass a `filter`, a partial index
  matching it serves both the read and the head query.

Worth testing directly, since none of it fails loudly: that a client-supplied value is ignored, that two rows written
in one transaction get increasing values, that an update moves the value forward, and that a row written while the
table holds a stamp in the future (put there with the trigger disabled) lands above that stamp.

#### The database clock stepping backwards

A position is a clock reading, so the one thing the design cannot see is the clock going backwards. The trigger above
covers it: `transaction_timestamp()` keeps a row at or after its own transaction's start, which handles a step during a
transaction, and the `max(updated_at)` floor keeps every row above every bookmark, which handles a step between
transactions and a step while the processor is down. What `BatchedAsyncEntityProcessor` adds is a report: the
boundary's `readAt` is the database clock, and when it reads earlier than the bookmark the clock has stepped. The
processor reads nothing until the clock passes the bookmark again, and says so through `clockStepLog` on each poll
until it does. That message is worth an alert if you see it, because it is also the only sign you would get on a table
whose trigger lacks the floor, where the same pause is a loss.

Slewing is not a step: a slewed clock runs slow but never goes backwards, and `clock_timestamp()` stays monotonic.
Chrony's default is to step only at startup and slew after that; classic ntpd steps for offsets over 128 ms.

#### Things to watch out for

- **Choosing the polled column.** Whatever you pass as `updatedAtColumn` has to advance on *every* change you care
  about, because that column is the only thing the processor watches. Polling a `created_at` works fine for
  insert-only tables, but if rows are later mutated — soft-deleted by stamping a `deleted_at`, say — those changes are
  invisible, since `created_at` doesn't move. A table without a real `updated_at` needs one adding, or a trigger to
  maintain it, before a processor over it can see updates rather than just inserts.
- **`updated_at` doesn't record commit order, which is what `safeBoundary` is for.** Postgres `now()` is fixed when a
  transaction *starts*, so a transaction beginning at 12:00 and committing at 12:30 makes rows visible half an hour
  after the timestamp they carry — and a reader whose bookmark has meanwhile passed 12:00 never sees them again, with
  nothing to alert on. Pass `PostgresXactStartSafeBoundary(database)`, which never lets the reader past the start of the
  oldest transaction that could still commit, and needs no tuning — it is derived entirely from database state. It
  has to read the **primary**: `pg_stat_activity` on a standby lists the standby's own sessions and nothing about the
  primary's writers, so it checks `pg_is_in_recovery()` and refuses with `SafeBoundaryUnsupportedException` rather than
  fail open. Give the `Database` the writer endpoint, or list the hosts and set pgjdbc's `targetServerType=primary`.
  It likewise refuses while any session in the database has `track_activities` off, since such a session publishes no
  `xact_start`, and when called inside a transaction of the caller's own, where Exposed would join it and the rows
  could be read from a snapshot older than the boundary. Its cost
  is that any long-running transaction in the same database holds the reader up. That is reported when a poll reads nothing *and* the newest row
  in the table sits more than `stallThreshold` (an hour) beyond the boundary — both timestamps database-generated, so no
  application clock is involved. The head of the table is only queried when the boundary is old enough for that to be
  possible, which it works out from the `readAt` that comes back with every boundary reading for free. A processor still working through rows below an old boundary is not a stall and says
  nothing. `stallBehaviour` decides what a stall does: `StallBehaviour.Throw`, the default, raises
  `SafeBoundaryStalledException` naming the session to close, while `StallBehaviour.LogAndContinue` reports it to a log
  and keeps polling. The `SafeBoundary` KDoc has the full argument.
- **Positions are `java.time.LocalDateTime` holding UTC, not joda `DateTime`** — the opposite of the event-sourcing
  side, which is joda throughout. A position is read straight out of a `timestamp without time zone` column and
  carried unconverted, so it means whatever the column holds. Nothing here converts between zones, so a column stamped
  in local time would be compared against a UTC boundary and be wrong by the offset; stamp it in UTC and every clock in
  this API agrees with it. Being nanosecond-precision, a `LocalDateTime` round-trips a `timestamp` exactly, so the
  column needs no particular precision.
- **Positions and time zones: map the column with `utcDatetime`, not Exposed's `datetime`.** Exposed's type moves
  values through `java.sql.Timestamp`, which is an instant, so each read and each bound parameter is converted from
  wall-clock time to an instant and back through the JVM's default zone. That cancels out except in the hour a DST
  transition skips, where the wall-clock value does not exist: on a JVM in `Australia/Melbourne`, a column value of
  `02:30` on the first Sunday of October reads back as `03:30`, and a position of `02:30` reaches Postgres as `03:30`.
  A bookmark saved from a row in that hour lands an hour ahead of real time, and every row committed in the following
  hour sits below it and is skipped. `RelationalDatabaseEntitySource` and the bookmark store read and bind positions
  through Kestrel's `UtcLocalDateTimeColumnType` whatever the column is mapped with, so this cannot happen to them.
  It can still happen in two places you own: `rowToEntity`, if it reads the column through a `datetime` mapping, and
  a hand-written `EntitySource`, which binds `after` and `safeBefore` for itself and has to do so with `utcDatetime`,
  or JDBC's `setObject`/`getObject` with `LocalDateTime`. Running the JVM in UTC avoids the whole class of problem,
  but is not something a library can see.
- **You only ever see current state.** Unlike an event stream, a table exposes the latest version of each row. A row
  updated twice in quick succession may only be processed once, rows are seen in `updated_at` order rather than
  creation order, and deletes aren't visible at all unless they're soft deletes. Entity-processors need to be
  idempotent for the same reasons event-processors do.
- **Don't `filter` out soft-deleted rows.** It looks like the obvious use for `filter`, and it is the one thing a
  published projection must not do: a soft delete is the *only* way a deletion can reach a projection at all, since a
  hard-deleted row simply stops existing. Filtering the flag out means the row stops being updated rather than being
  reported as deleted, so a consumer keeps the last value it saw forever, with nothing anywhere recording that the row
  went away. Read the flag in `rowToEntity` and let the processor decide what a delete means — a tombstone, usually.
  Keep `filter` for narrowing *which* rows a processor is responsible for, such as one account or one shard.

## Resources

- [CQRS article by Martin Fowler](https://trello.com/c/71yvoeq9/81-cqrs-pattern-martin-fowler)
- [Event-sourcing talk by Sebastian von Conrad](https://www.youtube.com/watch?v=iGt0DBOWDTs)
- [CQRS and event-sourcing FAQ](https://cqrs.nu/Faq)
- [Event-sourcing basic concepts](https://dev.to/cultureamp/event-sourcing-basic-concepts-52ik)
