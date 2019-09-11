package survey.thing

import eventsourcing.*
import java.util.UUID

sealed class ThingCommand : Command

sealed class ThingCreationCommand : ThingCommand(), CreationCommand
data class CreateThing(override val aggregateId: UUID) : ThingCreationCommand()

sealed class ThingUpdateCommand :ThingCommand(), UpdateCommand
data class Tweak(override val aggregateId: UUID, val tweak: String) : ThingUpdateCommand()
data class Bop(override val aggregateId: UUID) : ThingUpdateCommand()

sealed class ThingEvent : Event

data class ThingCreated(override val aggregateId: UUID) : ThingEvent(), CreationEvent

sealed class ThingUpdateEvent : ThingEvent(), UpdateEvent
data class Tweaked(override val aggregateId: UUID, val tweak: String) : ThingUpdateEvent()
data class Bopped(override val aggregateId: UUID) : ThingUpdateEvent()

data class ThingAggregate(override val aggregateId: UUID, val tweaks: List<String> = emptyList(), val bops: List<Bopped> = emptyList()) : Aggregate {
    constructor(event: ThingCreated) : this(event.aggregateId)

    companion object {
        fun create(command: ThingCreationCommand): Either<CommandError, ThingCreated> = when(command){
            is CreateThing -> Right(ThingCreated(command.aggregateId))
        }
    }

    fun updated(event: ThingUpdateEvent): ThingAggregate = when(event){
        is Tweaked -> this.copy(tweaks = tweaks + event.tweak)
        is Bopped -> this.copy(bops = bops + event)
    }

    fun update(command: ThingUpdateCommand): Either<CommandError, List<ThingUpdateEvent>> = when(command) {
        is Tweak -> Right.list(Tweaked(command.aggregateId, command.tweak))
        is Bop -> Right.list(Bopped(command.aggregateId))
    }
}
