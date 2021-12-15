package com.cultureamp.eventsourcing.example

import com.cultureamp.eventsourcing.Command
import com.cultureamp.eventsourcing.CreationCommand
import com.cultureamp.eventsourcing.CreationEvent
import com.cultureamp.eventsourcing.DomainError
import com.cultureamp.eventsourcing.DomainEvent
import com.cultureamp.eventsourcing.EventMetadata
import com.cultureamp.eventsourcing.Left
import com.cultureamp.eventsourcing.Right
import com.cultureamp.eventsourcing.SimpleAggregate
import com.cultureamp.eventsourcing.SimpleAggregateConstructor
import com.cultureamp.eventsourcing.UpdateCommand
import com.cultureamp.eventsourcing.UpdateEvent
import com.cultureamp.eventsourcing.sample.StandardEventMetadata
import java.util.*

sealed class SimpleThingCommand : Command

data class CreateSimpleThing(override val aggregateId: UUID) : SimpleThingCommand(), CreationCommand

sealed class SimpleThingUpdateCommand : SimpleThingCommand(), UpdateCommand
data class Twerk(override val aggregateId: UUID, val tweak: String) : SimpleThingUpdateCommand()
data class Boop(override val aggregateId: UUID) : SimpleThingUpdateCommand()
data class Bang(override val aggregateId: UUID) : SimpleThingUpdateCommand()

sealed class SimpleThingEvent : DomainEvent

object SimpleThingCreated : SimpleThingEvent(), CreationEvent

sealed class SimpleThingUpdateEvent : SimpleThingEvent(), UpdateEvent
data class Twerked(val tweak: String) : SimpleThingUpdateEvent()
object Booped : SimpleThingUpdateEvent()

sealed class SimpleThingError : DomainError
object Banged : SimpleThingError()

data class SimpleThingAggregate(val tweaks: List<String> = emptyList(), val boops: List<Booped> = emptyList()) : SimpleAggregate<SimpleThingUpdateCommand, SimpleThingUpdateEvent> {
    companion object : SimpleAggregateConstructor<CreateSimpleThing, SimpleThingCreated, SimpleThingUpdateCommand, SimpleThingUpdateEvent> {
        override fun created(event: SimpleThingCreated) = SimpleThingAggregate()

        override fun create(command: CreateSimpleThing) = Right(SimpleThingCreated)
    }

    override fun updated(event: SimpleThingUpdateEvent) = when(event){
        is Twerked -> this.copy(tweaks = tweaks + event.tweak)
        Booped -> this.copy(boops = boops + Booped)
    }

    override fun update(command: SimpleThingUpdateCommand) = when(command) {
        is Twerk -> Right.list(Twerked(command.tweak))
        is Boop -> Right.list(Booped)
        is Bang -> Left(Banged)
    }
}
