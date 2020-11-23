# Kestrel (Kotlin Event-Sourcing)

A framework for building event-sourced, CQRS applications in Kotlin.

## Summary

Event-sourcing is an architectural paradigm wherein application state is modelled and stored as an immutable sequence of semantic events which are meaningful in your application's domain.

CQRS, Command/Query Responsibility Segregation, describes a pattern in which 
write (command) actions and read (query) actions are codified in entirely separately classes, models and pathways 
through your system. 

Used in tandem, event-sourcing and CQRS provide a powerful and flexible architectural pattern. In
an event-sourced, CQRS system, writes typically happen via an event-centric domain model, also know as "Aggregates", and 
these changes propagate through to "projections" of those events to be read from by the view side of the application.
Events are thus considered the source of truth, while projections are disposable and can be rebuilt by reprocessing the historical events.

**Kes**trel is a **K**otlin **E**vent-**S**ourcing and CQRS framework that strives for 
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
blocked like commands. Event exist in an immutable event stream and once they exist need to be handled forever. At an 
implementation detail an `Event` is a wrapper around a `DomainEvent` with additional metadata attached.*
- [**CommandGateway**](https://github.com/cultureamp/kotlin-eventsourcing/blob/master/src/main/kotlin/com/cultureamp/eventsourcing/CommandGateway.kt) -
*The interface through which commands make their way through to aggregates. It's responsible for routing commands
to aggregates, and orchestrates the loading and saving aggregates through events and the `EventStore`.*
- [**EventStore**](https://github.com/cultureamp/kotlin-eventsourcing/blob/master/src/main/kotlin/com/cultureamp/eventsourcing/EventStore.kt) -
*Implements two interfaces, an `EventSink`, for saving events for aggregates, and an `EventSource`, for retrieving those
events. In general, the Event Store should only ever be written to via the `CommandGateway` and read from via an 
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

## Getting Started

### Sample App

See the [sample app](https://github.com/cultureamp/kotlin-es-sample-service) for an example of create a web-app built
on top of this framework.

### Adding as a dependency

Gradle:

```
repositories {
    maven { url 'https://package-repository.continuous-integration.cultureamp.net/repository/maven-snapshots' }
    maven { url 'https://package-repository.continuous-integration.cultureamp.net/repository/maven-releases' }
}

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
val routes = listOf(
    Route.from(SimpleThingAggregate)
)
val commandGateway = CommandGateway(eventStore, routes)
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
val routes = listOf(
    Route.from(
        SurveyAggregate.Companion::create.partial(SurveyNameAlwaysAvailable),
        SurveyAggregate::update.partial2(SurveyNameAlwaysAvailable),
        ::SurveyAggregate,
        SurveyAggregate::updated
    )
)
val commandGateway = CommandGateway(eventStore, routes)
```

If you happen to have a "stateless" aggregate that doesn't need to update it's internal state to handle commands, you
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
val routes = listOf(
    Route.fromStateless(
        PaymentSagaAggregate::create,
        PaymentSagaAggregate::update,
        PaymentSagaAggregate
    )
)
val gateway = CommandGateway(eventStore, routes)
```

### Event-processors (Projectors and Reactors)

KES offers multiple ways of defining your event-processors depending on your needs.

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

## Trello

https://trello.com/b/9mZdY0ZS/kotlin-event-sourcing

## Resources

- [CQRS article by Martin Fowler](https://trello.com/c/71yvoeq9/81-cqrs-pattern-martin-fowler)
- [Event-sourcing talk by Sebastian von Conrad](https://www.youtube.com/watch?v=iGt0DBOWDTs)
- [CQRS and event-sourcing FAQ](https://cqrs.nu/Faq) 
