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

sealed class SurveyError
object AlreadyRenamed : SurveyError()
object AlreadyDeleted : SurveyError()
object NotDeleted : SurveyError()

data class SurveyAggregateRoot(override val uuid: UUID, val name: Map<Locale, String>, val accountId: UUID, val deleted: Boolean = false) : AggregateRoot<SurveyUpdateCommand, SurveyUpdateEvent, SurveyError, SurveyAggregateRoot> {
    companion object : AggregateRootFactory<SurveyCreationCommand, SurveyCreationEvent, SurveyAggregateRoot> {
        override fun create(event: SurveyCreationEvent): SurveyAggregateRoot = when (event) {
            is Created -> with(event) { SurveyAggregateRoot(aggregateId, name, accountId) }
            is Snapshot -> with(event) { SurveyAggregateRoot(aggregateId, name, accountId, deleted) }
        }

        override fun handle(command: SurveyCreationCommand): Result<SurveyCreationEvent, Error> = when (command) {
            is Create -> with(command) { Result.Success(Created(aggregateId, name, accountId, createdAt)) }
        }
    }

    override fun update(event: SurveyUpdateEvent): SurveyAggregateRoot = when (event) {
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

enum class Locale {
    en, de
}

interface AggregateRoot<UC: UpdateCommand, UE: UpdateEvent, Error, Self : AggregateRoot<UC, UE, Error, Self>> {
    val uuid: UUID
    fun update(event: UE): Self
    fun handle(command: UC): Result<UE, Error>
}

interface AggregateRootFactory<CC: CreationCommand, CE: CreationEvent, AggregateType> {
    fun create(event: CE): AggregateType
    fun handle(command: CC): Result<CreationEvent, Error>
}

//interface AggregateRootRepository {
//    fun <UC: UpdateCommand, UE: UpdateEvent, Error, Self : AggregateRoot<UC, UE, Error, Self>> get(aggregateId: UUID): AggregateRoot<UC, UE, Error, AggregateRoot<UC, UE, Error>>
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

class AggregateRootRegistry(val list: List<AggregateRootFactory<out CreationCommand, out CreationEvent, Self: AggregateRoot<UpdateCommand, UpdateEvent, Any, Self>>>) {
    fun <C : CreationCommand, E: CreationEvent>aggregateRootFactoryFor(command: C): AggregateRootFactory<C>
}

fun appWiring() {
    val factory: AggregateRootFactory<SurveyCreationCommand, SurveyCreationEvent, SurveyAggregateRoot> = SurveyAggregateRoot.Companion
    val aggregateRootRegistry = AggregateRootRegistry(listOf(factory))
}

sealed class Result<out V, out E> {
    data class Success<V>(val values: List<V>) : Result<V, Nothing>() {
        constructor(vararg values: V) : this(listOf(*values))
    }

    data class Failure<E>(val error: E) : Result<Nothing, E>()
}