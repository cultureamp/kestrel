package survey.thing

import eventsourcing.*
import java.util.UUID

sealed class ThingCommand : Command

sealed class ThingCreationCommand : ThingCommand(), CreationCommand
data class CreateThing(override val aggregateId: UUID) : ThingCreationCommand()

sealed class ThingUpdateCommand :ThingCommand(), UpdateCommand
data class Tweak(override val aggregateId: UUID, val tweak: String) : ThingUpdateCommand()
data class Bop(override val aggregateId: UUID) : ThingUpdateCommand()
data class Explode(override val aggregateId: UUID) : ThingUpdateCommand()

sealed class ThingEvent : Event

sealed class ThingCreationEvent : CreationEvent
data class ThingCreated(override val aggregateId: UUID) : ThingCreationEvent()

sealed class ThingUpdateEvent : ThingEvent(), UpdateEvent
data class Tweaked(override val aggregateId: UUID, val tweak: String) : ThingUpdateEvent()
data class Bopped(override val aggregateId: UUID) : ThingUpdateEvent()

sealed class ThingError : CommandError
object Expoded : ThingError()
object Unboppable : ThingError()

interface ThingCommandProjection {
    fun isBoppable(): Boolean
}
object AlwaysBoppable : ThingCommandProjection {
    override fun isBoppable() = true
}

data class ThingAggregate(override val aggregateId: UUID, val tweaks: List<String> = emptyList(), val bops: List<Bopped> = emptyList()) : AggregateWithProjection<ThingUpdateCommand, ThingUpdateEvent, ThingError, ThingCommandProjection, ThingAggregate> {
    companion object : AggregateConstructorWithProjection<ThingCreationCommand, ThingCreationEvent, ThingError, ThingUpdateCommand, ThingUpdateEvent, ThingCommandProjection, ThingAggregate> {
        override fun created(event: ThingCreationEvent): ThingAggregate = when(event) {
            is ThingCreated -> ThingAggregate(event.aggregateId)
        }

        override fun create(projection: ThingCommandProjection, command: ThingCreationCommand): Either<ThingError, ThingCreationEvent> = when(command){
            is CreateThing -> Right(ThingCreated(command.aggregateId))
        }
    }

    override fun updated(event: ThingUpdateEvent): ThingAggregate = when(event){
        is Tweaked -> this.copy(tweaks = tweaks + event.tweak)
        is Bopped -> this.copy(bops = bops + event)
    }

    override fun update(projection: ThingCommandProjection, command: ThingUpdateCommand): Either<ThingError, List<ThingUpdateEvent>> = when(command) {
        is Tweak -> Right.list(Tweaked(command.aggregateId, command.tweak))
        is Bop -> when(projection.isBoppable()) {
            false -> Left(Unboppable)
            true -> Right.list(Bopped(command.aggregateId))
        }
        is Explode -> Left(Expoded)
    }
}
