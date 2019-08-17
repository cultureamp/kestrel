package survey.design

import io.kotlintest.shouldBe
import io.kotlintest.specs.ShouldSpec
import java.util.*

class GenericsSpec : ShouldSpec({
    "creation" {
        should("work") {
            val registry = mapOf(
                FooCommand::class to FooAggregateConstructor,
                QuuxCommand::class to QuuxAggregateConstructor
            )
            val fooCommand = FooBar(UUID.randomUUID())
            val constructor = registry.entries.find { entry -> entry.key.isInstance(fooCommand) }?.value
            val result = constructor?.create(fooCommand)?.let { event ->
                EventStore.save(event)
                true
            } ?: false
            result shouldBe true
        }
    }
//
//    "update" {
//        should("work") {
//            val commandRegistry = mapOf(
//                FooCommand::class to FooAggregateConstructor,
//                QuuxCommand::class to QuuxAggregateConstructor
//            )
//            val eventRegistry = mapOf(
//                FooEvent::class to FooAggregateConstructor,
//                QuuxEvent::class to QuuxAggregateConstructor
//            )
//            val fooCommand = FooBar(UUID.randomUUID())
//            val constructor = eventRegistry.entries.find { entry -> entry.key.isInstance(fooCommand) }?.value
//            val result = constructor?.create(fooCommand)?.let { event ->
//                EventStore.save(event)
//                true
//            } ?: false
//            result shouldBe true
//        }
//    }
})

interface Command {
    val aggregateId: UUID
}

interface Event {
    val aggregateId: UUID
}

interface Aggregate<in C : Command, E : Event, Self : Aggregate<C, E, Self>> {
    fun update(command: C): E
}

interface CreationCommandHandler<in C: Command, E: Event, Self : Aggregate<C, E, Self>> {
    fun created(event: E): Self
    fun create(command: C): E
}

sealed class FooCommand : Command
data class FooBar(override val aggregateId: UUID) : FooCommand()
data class FooBaz(override val aggregateId: UUID) : FooCommand()

sealed class QuuxCommand : Command
data class QuuxBar(override val aggregateId: UUID) : QuuxCommand()
data class QuuxBaz(override val aggregateId: UUID) : QuuxCommand()

sealed class FooEvent : Event
data class BarFooed(override val aggregateId: UUID) : FooEvent()
data class BazFooed(override val aggregateId: UUID) : FooEvent()

sealed class QuuxEvent : Event
data class BarQuuxed(override val aggregateId: UUID) : QuuxEvent()
data class BazQuuxed(override val aggregateId: UUID) : QuuxEvent()

object FooAggregateConstructor : CreationCommandHandler<FooCommand, FooEvent, FooAggregate> {
    override fun created(event: FooEvent): FooAggregate {
        return FooAggregate(event.aggregateId)
    }

    override fun create(command: FooCommand): FooEvent {
        return BarFooed(command.aggregateId)
    }
}

object QuuxAggregateConstructor : CreationCommandHandler<QuuxCommand, QuuxEvent, QuuxAggregate> {
    override fun created(event: QuuxEvent): QuuxAggregate {
        return QuuxAggregate(event.aggregateId)
    }

    override fun create(command: QuuxCommand): QuuxEvent {
        return BarQuuxed(command.aggregateId)
    }
}

data class FooAggregate(val aggregateId: UUID) : Aggregate<FooCommand, FooEvent, FooAggregate> {
    override fun update(command: FooCommand): FooEvent {
        return BarFooed(command.aggregateId)
    }
}

data class QuuxAggregate(val aggregateId: UUID) : Aggregate<QuuxCommand, QuuxEvent, QuuxAggregate> {
    override fun update(command: QuuxCommand): QuuxEvent {
        return BarQuuxed(command.aggregateId)
    }

}

object EventStore {
    fun save(event: Event): Unit {
    }

    fun eventsFor(uuid: UUID): List<Event> {
        return emptyList()
    }
}
