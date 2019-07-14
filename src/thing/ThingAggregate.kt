package thing

import eventsourcing.*
import java.util.UUID

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

data class ThingAggregate(override val aggregateId: UUID, val tweaks: List<String> = emptyList(), val bops: List<Bopped> = emptyList()) : Aggregate<ThingUpdateCommand, ThingUpdateEvent, CommandError, ThingAggregate> {
    companion object : AggregateConstructor<ThingCreationCommand, ThingCreationEvent, CommandError, ThingAggregate> {
        override fun create(event: ThingCreationEvent): ThingAggregate = when(event) {
            is ThingCreated -> ThingAggregate(event.aggregateId)
        }

        override fun handle(command: ThingCreationCommand): Result<CreationEvent, CommandError> = when(command) {
            is CreateThing -> Result.Success(ThingCreated(command.aggregateId))
        }
    }

    override fun update(event: ThingUpdateEvent): ThingAggregate = when(event){
        is Tweaked -> this.copy(tweaks = tweaks + event.tweak)
        Bopped -> this.copy(bops = bops + Bopped)
    }

    override fun handle(command: ThingUpdateCommand): Result<ThingUpdateEvent, CommandError> = when(command) {
        is Tweak -> Result.Success(Tweaked(command.tweak))
        is Bop -> Result.Success(Bopped)
    }
}
