package com.cultureamp.eventsourcing.example

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.cultureamp.eventsourcing.AggregateConstructorWithProjection
import com.cultureamp.eventsourcing.AggregateWithProjection
import com.cultureamp.eventsourcing.Command
import com.cultureamp.eventsourcing.CreationCommand
import com.cultureamp.eventsourcing.CreationEvent
import com.cultureamp.eventsourcing.DomainError
import com.cultureamp.eventsourcing.DomainEvent
import com.cultureamp.eventsourcing.UpdateCommand
import com.cultureamp.eventsourcing.UpdateEvent
import com.cultureamp.eventsourcing.sample.StandardEventMetadata
import java.util.*

sealed class ThingCommand : Command

sealed class ThingCreationCommand : ThingCommand(), CreationCommand
data class CreateThing(override val aggregateId: UUID) : ThingCreationCommand()

sealed class ThingUpdateCommand : ThingCommand(), UpdateCommand
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

data class ThingAggregate(val tweaks: List<String> = emptyList(), val bops: List<Bopped> = emptyList()) : AggregateWithProjection<ThingUpdateCommand, ThingUpdateEvent, ThingError, ThingCommandProjection, StandardEventMetadata, ThingAggregate> {
    companion object : AggregateConstructorWithProjection<ThingCreationCommand, ThingCreationEvent, ThingError, ThingUpdateCommand, ThingUpdateEvent, ThingCommandProjection, StandardEventMetadata, ThingAggregate> {
        override fun created(event: ThingCreationEvent): ThingAggregate = when(event) {
            is ThingCreated -> ThingAggregate()
        }

        override fun create(projection: ThingCommandProjection, command: ThingCreationCommand, metadata: StandardEventMetadata): Either<ThingError, Pair<ThingCreationEvent, List<ThingUpdateEvent>>> = when (command) {
            is CreateThing -> Either.Right(ThingCreated to emptyList())
        }

        override fun aggregateType() = "thing"
    }

    override fun updated(event: ThingUpdateEvent): ThingAggregate = when(event){
        is Tweaked -> this.copy(tweaks = tweaks + event.tweak)
        is Bopped -> this.copy(bops = bops + event)
    }

    override fun update(projection: ThingCommandProjection, command: ThingUpdateCommand, metadata: StandardEventMetadata): Either<ThingError, List<ThingUpdateEvent>> = when(command) {
        is Tweak -> listOf(Tweaked(command.tweak)).right()
        is Bop -> when(projection.isBoppable()) {
            false -> Unboppable.left()
            true -> listOf(Bopped).right()
        }
        is Explode -> Expoded.left()
    }
}
