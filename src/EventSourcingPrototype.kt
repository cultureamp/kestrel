import java.util.UUID
import java.util.Date

sealed class SurveyCreationCommand : CreationCommand
data class Create(override val aggregateId: UUID, val name: Map<Locale, String>, val accountId: UUID, val createdAt: Date) : SurveyCreationCommand()

sealed class SurveyUpdateCommand : UpdateCommand
data class Rename(override val aggregateId: UUID, val newName: String, val locale: Locale, val renamedAt: Date) : SurveyUpdateCommand()
data class Delete(override val aggregateId: UUID, val deletedAt: Date) : SurveyUpdateCommand()
data class Restore(override val aggregateId: UUID, val restoredAt: Date) : SurveyUpdateCommand()

sealed class SurveyCreationEvent : CreationEvent
data class Created(override val aggregateId: UUID, val name: Map<Locale, String>, val accountId: UUID, val createdAt: Date) : SurveyCreationEvent()
data class Snapshot(override val aggregateId: UUID, val name: Map<Locale, String>, val accountId: UUID, val deleted: Boolean, val snapshottedAt: Date) : SurveyCreationEvent()

sealed class SurveyUpdateEvent : UpdateEvent
data class Renamed(val name: String, val locale: Locale, val namedAt: Date) : SurveyUpdateEvent()
data class Deleted(val deletedAt: Date) : SurveyUpdateEvent()
data class Restored(val restoredAt: Date) : SurveyUpdateEvent()

sealed class SurveyError : Error
object AlreadyRenamed : SurveyError()
object AlreadyDeleted : SurveyError()
object NotDeleted : SurveyError()

data class SurveyAggregate(override val aggregateId: UUID, val name: Map<Locale, String>, val accountId: UUID, val deleted: Boolean = false) : Aggregate<SurveyUpdateCommand, SurveyUpdateEvent, SurveyError, SurveyAggregate> {
    companion object : AggregateConstructor<SurveyCreationCommand, SurveyCreationEvent, SurveyError, SurveyAggregate> {
        override fun create(event: SurveyCreationEvent): SurveyAggregate = when (event) {
            is Created -> with(event) { SurveyAggregate(aggregateId, name, accountId) }
            is Snapshot -> with(event) { SurveyAggregate(aggregateId, name, accountId, deleted) }
        }

        override fun handle(command: SurveyCreationCommand): Result<SurveyCreationEvent, SurveyError> = when (command) {
            is Create -> with(command) { Result.Success(Created(aggregateId, name, accountId, createdAt)) }
        }
    }

    override fun update(event: SurveyUpdateEvent): SurveyAggregate = when (event) {
        is Renamed -> this.copy(name = name.plus(event.locale to event.name))
        is Deleted -> this.copy(deleted = true)
        is Restored -> this.copy(deleted = false)
    }

    override fun handle(command: SurveyUpdateCommand): Result<SurveyUpdateEvent, SurveyError> = when (command) {
        is Rename -> when (name.get(command.locale)) {
            command.newName -> Result.Failure(AlreadyRenamed)
            else -> with(command) { Result.Success(Renamed(newName, locale, renamedAt)) }
        }
        is Delete -> when (deleted) {
            true -> Result.Failure(AlreadyDeleted)
            false -> with(command) { Result.Success(Deleted(deletedAt)) }
        }
        is Restore -> when (deleted) {
            true -> with(command) { Result.Success(Restored(restoredAt)) }
            false -> Result.Failure(NotDeleted)
        }
    }
}

sealed class ThingCreationCommand : CreationCommand
data class CreateThing(override val aggregateId: UUID) : ThingCreationCommand()

sealed class ThingUpdateCommand : UpdateCommand
data class Tweak(override val aggregateId: UUID, val tweak: String) : ThingUpdateCommand()
data class Bop(override val aggregateId: UUID) : ThingUpdateCommand()

sealed class ThingCreationEvent : CreationEvent
data class ThingCreated(override val aggregateId: UUID) : ThingCreationEvent()

sealed class ThingUpdateEvent : UpdateEvent
data class Tweaked(val tweak: String) : ThingUpdateEvent()
object Bopped : ThingUpdateEvent()

data class ThingAggregate(override val aggregateId: UUID, val tweaks: List<String> = emptyList(), val bops: List<Bopped> = emptyList()) : Aggregate<ThingUpdateCommand, ThingUpdateEvent, Error, ThingAggregate> {
    companion object : AggregateConstructor<ThingCreationCommand, ThingCreationEvent, Error, ThingAggregate> {
        override fun create(event: ThingCreationEvent): ThingAggregate = when(event) {
            is ThingCreated -> ThingAggregate(event.aggregateId)
        }

        override fun handle(command: ThingCreationCommand): Result<CreationEvent, Error> = when(command) {
            is CreateThing -> Result.Success(ThingCreated(command.aggregateId))
        }
    }

    override fun update(event: ThingUpdateEvent): ThingAggregate = when(event){
        is Tweaked -> this.copy(tweaks = tweaks + event.tweak)
        Bopped -> this.copy(bops = bops + Bopped)
    }

    override fun handle(command: ThingUpdateCommand): Result<ThingUpdateEvent, Error> = when(command) {
        is Tweak -> Result.Success(Tweaked(command.tweak))
        is Bop -> Result.Success(Bopped)
    }
}

enum class Locale {
    en, de
}

interface Aggregate<UC: UpdateCommand, UE: UpdateEvent, E: Error, Self : Aggregate<UC, UE, E, Self>> {
    val aggregateId: UUID
    fun update(event: UE): Self
    fun handle(command: UC): Result<UE, E>
}

interface AggregateConstructor<CC: CreationCommand, CE: CreationEvent, E: Error, AggregateType> {
    fun create(event: CE): AggregateType
    fun handle(command: CC): Result<CreationEvent, E>
}

//interface AggregateRootRepository {
//    fun <UC: UpdateCommand, UE: UpdateEvent, Error, Self : Aggregate<UC, UE, Error, Self>> get(aggregateId: UUID): Aggregate<UC, UE, Error, Aggregate<UC, UE, Error>>
//}

interface Command {
    val aggregateId: UUID
}

interface CreationCommand : Command {
    override val aggregateId: UUID
}

interface UpdateCommand : Command {
    override val aggregateId: UUID
}

interface Event

interface CreationEvent : Event {
    val aggregateId: UUID
}

interface UpdateEvent : Event

interface Error

class AggregateRootRegistry(val list: List<AggregateConstructor<out CreationCommand, out CreationEvent, out Error, out Aggregate<out UpdateCommand, out UpdateEvent, out Error, *>>>) {
    fun <CC : CreationCommand>aggregateRootConstructorFor(creationCommand: CC): AggregateConstructor<CC, *, *, *>? {
        return null
    }
}

fun appWiring() {
    val first: AggregateConstructor<SurveyCreationCommand, SurveyCreationEvent, SurveyError, SurveyAggregate> = SurveyAggregate.Companion
    val second: AggregateConstructor<ThingCreationCommand, ThingCreationEvent, Error, ThingAggregate> = ThingAggregate.Companion
    val constructors = listOf(first, second)
    val aggregateRootRegistry = AggregateRootRegistry(constructors)
    val aggregateRootConstructor = aggregateRootRegistry.aggregateRootConstructorFor(CreateThing(UUID.randomUUID()))
}

sealed class Result<out V, out E> {
    data class Success<V>(val values: List<V>) : Result<V, Nothing>() {
        constructor(vararg values: V) : this(listOf(*values))
    }

    data class Failure<E>(val error: E) : Result<Nothing, E>()
}