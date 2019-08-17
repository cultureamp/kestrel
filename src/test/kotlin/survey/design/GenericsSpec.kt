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
})

interface Command {
    val aggregateId: UUID
}

interface Event {
    val aggregateId: UUID
}

interface CreationCommandHandler<in C: Command, E: Event> {
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

object FooAggregateConstructor : CreationCommandHandler<FooCommand, FooEvent> {
    override fun create(command: FooCommand): FooEvent {
        return BarFooed(command.aggregateId)
    }
}

object QuuxAggregateConstructor : CreationCommandHandler<QuuxCommand, QuuxEvent> {
    override fun create(command: QuuxCommand): QuuxEvent {
        return BarQuuxed(command.aggregateId)
    }
}

object EventStore {
    fun save(event: Event): Unit {
    }
}
