package com.cultureamp.eventsourcing.fixtures

import com.cultureamp.eventsourcing.Command
import com.cultureamp.eventsourcing.CreationCommand
import com.cultureamp.eventsourcing.CreationEvent
import com.cultureamp.eventsourcing.DomainError
import com.cultureamp.eventsourcing.DomainEvent
import com.cultureamp.eventsourcing.Left
import com.cultureamp.eventsourcing.Right
import com.cultureamp.eventsourcing.SimpleAggregate
import com.cultureamp.eventsourcing.SimpleAggregateConstructor
import com.cultureamp.eventsourcing.UpdateCommand
import com.cultureamp.eventsourcing.UpdateEvent
import java.util.*

sealed class SimpleThingCommand : Command

sealed class SimpleThingCreationCommand : SimpleThingCommand(), CreationCommand
data class CreateSimpleThing(override val aggregateId: UUID) : SimpleThingCreationCommand()

sealed class SimpleThingUpdateCommand : SimpleThingCommand(), UpdateCommand
data class Twerk(override val aggregateId: UUID, val tweak: String) : SimpleThingUpdateCommand()
data class Boop(override val aggregateId: UUID) : SimpleThingUpdateCommand()
data class Bang(override val aggregateId: UUID) : SimpleThingUpdateCommand()

sealed class SimpleThingEvent : DomainEvent

sealed class SimpleThingCreationEvent : CreationEvent
object SimpleThingCreated : SimpleThingCreationEvent()

sealed class SimpleThingUpdateEvent : SimpleThingEvent(), UpdateEvent
data class Twerked(val tweak: String) : SimpleThingUpdateEvent()
object Booped : SimpleThingUpdateEvent()

sealed class SimpleThingError : DomainError
object Banged : SimpleThingError()

data class SimpleThingAggregate(val tweaks: List<String> = emptyList(), val boops: List<Booped> = emptyList()) : SimpleAggregate<SimpleThingUpdateCommand, SimpleThingUpdateEvent> {
    companion object : SimpleAggregateConstructor<SimpleThingCreationCommand, SimpleThingCreationEvent, SimpleThingUpdateCommand, SimpleThingUpdateEvent> {
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
