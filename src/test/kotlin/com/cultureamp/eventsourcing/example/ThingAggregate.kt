package com.cultureamp.eventsourcing.example

import com.cultureamp.eventsourcing.AggregateConstructorWithProjection
import com.cultureamp.eventsourcing.AggregateWithProjection
import com.cultureamp.eventsourcing.Command
import com.cultureamp.eventsourcing.CreationCommand
import com.cultureamp.eventsourcing.CreationEvent
import com.cultureamp.eventsourcing.DomainError
import com.cultureamp.eventsourcing.DomainEvent
import com.cultureamp.eventsourcing.Result
import com.cultureamp.eventsourcing.Failure
import com.cultureamp.eventsourcing.Success
import com.cultureamp.eventsourcing.UpdateCommand
import com.cultureamp.eventsourcing.UpdateEvent
import java.util.*

sealed class ThingCommand : Command<ThingError>

sealed class ThingCreationCommand : ThingCommand(), CreationCommand<ThingError>
data class CreateThing(override val aggregateId: UUID) : ThingCreationCommand()

sealed class ThingUpdateCommand : ThingCommand(), UpdateCommand<ThingError>
data class Tweak(override val aggregateId: UUID, val tweak: String) : ThingUpdateCommand()
data class Bop(override val aggregateId: UUID) : ThingUpdateCommand()
data class Explode(override val aggregateId: UUID) : ThingUpdateCommand()

sealed class ThingEvent : DomainEvent

sealed class ThingCreationEvent : CreationEvent
object ThingCreated : ThingCreationEvent()

sealed class ThingUpdateEvent : ThingEvent(), UpdateEvent
data class Tweaked(val tweak: String) : ThingUpdateEvent()
object Bopped : ThingUpdateEvent()

sealed class ThingError : DomainError
object Expoded : ThingError()
object Unboppable : ThingError()

interface ThingCommandProjection {
    fun isBoppable(): Boolean
}
object AlwaysBoppable : ThingCommandProjection {
    override fun isBoppable() = true
}

data class ThingAggregate(val tweaks: List<String> = emptyList(), val bops: List<Bopped> = emptyList()) : AggregateWithProjection<ThingUpdateCommand, ThingUpdateEvent, ThingError, ThingCommandProjection, ThingAggregate> {
    companion object : AggregateConstructorWithProjection<ThingCreationCommand, ThingCreationEvent, ThingError, ThingUpdateCommand, ThingUpdateEvent, ThingCommandProjection, ThingAggregate> {
        override fun created(event: ThingCreationEvent): ThingAggregate = when(event) {
            is ThingCreated -> ThingAggregate()
        }

        override fun create(projection: ThingCommandProjection, command: ThingCreationCommand): Result<ThingError, ThingCreationEvent> = when(command){
            is CreateThing -> Success(ThingCreated)
        }
    }

    override fun updated(event: ThingUpdateEvent): ThingAggregate = when(event){
        is Tweaked -> this.copy(tweaks = tweaks + event.tweak)
        is Bopped -> this.copy(bops = bops + event)
    }

    override fun update(projection: ThingCommandProjection, command: ThingUpdateCommand): Result<ThingError, List<ThingUpdateEvent>> = when(command) {
        is Tweak -> Success.list(Tweaked(command.tweak))
        is Bop -> when(projection.isBoppable()) {
            false -> Failure(Unboppable)
            true -> Success.list(Bopped)
        }
        is Explode -> Failure(Expoded)
    }

    override fun aggregateType() = "thing"
}
