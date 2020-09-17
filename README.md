# Kotlin-Eventsourcing (KES)

A framework for event-sourced Kotlin apps

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
    implementation "com.cultureamp:kotlin-eventsourcing:{kes_version}"
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
        fun create(query: SurveyNamesQuery, command: SurveyCreationCommand): Either<SurveyError, Created> {
            return when (command) {
                is CreateSurvey -> when {
                    command.name.any { (locale, name) -> query.nameExistsFor(command.accountId, name, locale)} -> Left(SurveyNameNotUnique)
                    else -> Right(Created(command.name, command.accountId, command.createdAt))
                }
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

## Trello

https://trello.com/b/9mZdY0ZS/kotlin-event-sourcing
