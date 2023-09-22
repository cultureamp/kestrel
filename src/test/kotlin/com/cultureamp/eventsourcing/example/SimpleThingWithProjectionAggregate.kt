package com.cultureamp.eventsourcing.example

import arrow.core.left
import arrow.core.right
import com.cultureamp.eventsourcing.Command
import com.cultureamp.eventsourcing.CreationCommand
import com.cultureamp.eventsourcing.CreationEvent
import com.cultureamp.eventsourcing.DomainError
import com.cultureamp.eventsourcing.DomainEvent
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

        override fun create(projection: RandomNumberGenerator, command: CreateSimpleThingWithProjection) = SimpleThingWithProjectionCreated(projection.randomNumber()).right()
    }

    override fun updated(event: SimpleThingUpdateWithProjectionEvent) = when(event){
        is TwerkedWithProjection -> this.copy(tweaks = tweaks + event.tweak)
        is BoopedWithProjection -> this.copy(boops = boops + event)
    }

    override fun update(projection: RandomNumberGenerator, command: SimpleThingUpdateWithProjectionCommand) = when(command) {
        is TwerkWithProjection -> listOf(TwerkedWithProjection(command.tweak, projection.randomNumber())).right()
        is BoopWithProjection -> listOf(BoopedWithProjection).right()
        is BangWithProjection -> BangedWithProjection.left()
    }
}
