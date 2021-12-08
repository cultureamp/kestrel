package com.cultureamp.eventsourcing.example

import com.cultureamp.eventsourcing.Command
import com.cultureamp.eventsourcing.CreationCommand
import com.cultureamp.eventsourcing.CreationEvent
import com.cultureamp.eventsourcing.DomainError
import com.cultureamp.eventsourcing.DomainEvent
import com.cultureamp.eventsourcing.Left
import com.cultureamp.eventsourcing.Right
import com.cultureamp.eventsourcing.SimpleAggregateConstructorWithProjection
import com.cultureamp.eventsourcing.SimpleAggregateWithProjection
import com.cultureamp.eventsourcing.UpdateCommand
import com.cultureamp.eventsourcing.UpdateEvent
import java.util.UUID

sealed class SimpleThingWithProjectionCommand : Command

data class CreateSimpleThingWithProjection(override val aggregateId: UUID) : SimpleThingWithProjectionCommand(), CreationCommand

sealed class SimpleThingUpdateWithProjectionCommand : SimpleThingWithProjectionCommand(), UpdateCommand
data class TwerkWithProjection(override val aggregateId: UUID, val tweak: String) : SimpleThingUpdateWithProjectionCommand()
data class BoopWithProjection(override val aggregateId: UUID) : SimpleThingUpdateWithProjectionCommand()
data class BangWithProjection(override val aggregateId: UUID) : SimpleThingUpdateWithProjectionCommand()

sealed class SimpleThingWithProjectionEvent : DomainEvent

data class SimpleThingWithProjectionCreated(val randomNumber: Int) : SimpleThingWithProjectionEvent(), CreationEvent

sealed class SimpleThingUpdateWithProjectionEvent : SimpleThingWithProjectionEvent(), UpdateEvent
data class TwerkedWithProjection(val tweak: String, val randomNumber: Int) : SimpleThingUpdateWithProjectionEvent()
object BoopedWithProjection : SimpleThingUpdateWithProjectionEvent()

sealed class SimpleThingWithProjectionError : DomainError
object BangedWithProjection : SimpleThingWithProjectionError()

class RandomNumberGenerator {
    fun randomNumber(): Int = (0..10).random()
}

data class SimpleThingWithProjectionAggregate(val tweaks: List<String> = emptyList(), val boops: List<BoopedWithProjection> = emptyList()) : SimpleAggregateWithProjection<SimpleThingUpdateWithProjectionCommand, SimpleThingUpdateWithProjectionEvent, RandomNumberGenerator> {
    companion object : SimpleAggregateConstructorWithProjection<CreateSimpleThingWithProjection, SimpleThingWithProjectionCreated, SimpleThingUpdateWithProjectionCommand, SimpleThingUpdateWithProjectionEvent, RandomNumberGenerator> {
        override fun created(event: SimpleThingWithProjectionCreated) = SimpleThingWithProjectionAggregate()

        override fun create(projection: RandomNumberGenerator, command: CreateSimpleThingWithProjection) = Right(SimpleThingWithProjectionCreated(projection.randomNumber()))
    }

    override fun updated(event: SimpleThingUpdateWithProjectionEvent) = when(event){
        is TwerkedWithProjection -> this.copy(tweaks = tweaks + event.tweak)
        is BoopedWithProjection -> this.copy(boops = boops + event)
    }

    override fun update(projection: RandomNumberGenerator, command: SimpleThingUpdateWithProjectionCommand) = when(command) {
        is TwerkWithProjection -> Right.list(TwerkedWithProjection(command.tweak, projection.randomNumber()))
        is BoopWithProjection -> Right.list(BoopedWithProjection)
        is BangWithProjection -> Left(BangedWithProjection)
    }
}
