package com.cultureamp.eventsourcing

import io.kotest.core.spec.style.DescribeSpec
import org.jetbrains.exposed.sql.Database
import java.util.*

enum class PizzaTopping {
    CHEESE,
    TOMATO_PASTE,
    BASIL,
    HAM,
    PINEAPPLE
}

enum class PizzaStyle {
    MARGHERITA,
    HAWAIIAN
}

sealed class PizzaCommand : Command

sealed class PizzaCreationCommand : PizzaCommand(), CreationCommand
data class CreateClassicPizza(val baseStyle: PizzaStyle, override val aggregateId: UUID) : PizzaCreationCommand()
sealed class PizzaUpdateCommand : PizzaCommand(), UpdateCommand
data class AddTopping(override val aggregateId: UUID, val topping: PizzaTopping) : PizzaUpdateCommand()
data class EatPizza(override val aggregateId: UUID) : PizzaUpdateCommand()

sealed class PizzaEvent : DomainEvent
data class PizzaCreated(val baseStyle: PizzaStyle, val initialToppings: List<PizzaTopping>) : PizzaEvent(),
    CreationEvent

sealed class PizzaUpdateEvent : PizzaEvent(), UpdateEvent
data class PizzaToppingAdded(val newTopping: PizzaTopping) : PizzaUpdateEvent()
class PizzaEaten : PizzaUpdateEvent()

sealed class PizzaError : CommandError
class ToppingAlreadyPresent : PizzaError()
class PizzaAlreadyEaten : PizzaError()

data class PizzaAggregate(
    val baseStyle: PizzaStyle, val toppings: List<PizzaTopping>, val isEaten: Boolean = false
) : Aggregate {
    constructor(event: PizzaCreated) : this(baseStyle = event.baseStyle, toppings = event.initialToppings)

    fun updated(event: PizzaUpdateEvent): PizzaAggregate = when (event) {
        is PizzaToppingAdded -> this.copy(toppings = this.toppings + event.newTopping)
        is PizzaEaten -> this.copy(isEaten = true)
    }

    companion object {
        private fun classicToppings(style: PizzaStyle): List<PizzaTopping> = when (style) {
            PizzaStyle.MARGHERITA -> listOf(PizzaTopping.CHEESE, PizzaTopping.TOMATO_PASTE, PizzaTopping.BASIL)
            PizzaStyle.HAWAIIAN -> listOf(
                PizzaTopping.CHEESE,
                PizzaTopping.TOMATO_PASTE,
                PizzaTopping.HAM,
                PizzaTopping.PINEAPPLE
            )
        }


        fun create(command: PizzaCreationCommand): Either<PizzaError, PizzaCreated> = when (command) {
            is CreateClassicPizza -> {
                val initialToppings = classicToppings(command.baseStyle)
                Right(PizzaCreated(command.baseStyle, initialToppings))
            }
        }
    }

    fun update(command: PizzaUpdateCommand): Either<PizzaError, List<PizzaUpdateEvent>> = when (command) {
        is AddTopping -> when (toppings.contains(command.topping)) {
            true -> Left(ToppingAlreadyPresent())
            false -> Right(listOf(PizzaToppingAdded(command.topping)))
        }
        is EatPizza -> when (isEaten) {
            true -> Left(PizzaAlreadyEaten())
            false -> Right(listOf(PizzaEaten()))
        }
    }

}

class CommandGatewayIntegrationTest : DescribeSpec({
    val h2DbUrl = "jdbc:h2:mem:test;MODE=MySQL;DB_CLOSE_DELAY=-1;"
    val h2Driver = "org.h2.Driver"
    val db = Database.connect(url = h2DbUrl, driver = h2Driver)
    val eventStore = RelationalDatabaseEventStore.create(db)
    val registry = listOf(
        Configuration.from(
            PizzaAggregate.Companion::create,
            PizzaAggregate::update,
            {evt: PizzaCreated -> PizzaAggregate(evt)},
            PizzaAggregate::updated
        )
    )


    describe("CommandGateway") {
        it("sets and retrieves a bookmark") {
        }

        it("returns zero for an unknown bookmark") {
        }
    }
})


