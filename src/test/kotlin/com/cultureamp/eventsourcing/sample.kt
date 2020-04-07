package com.cultureamp.eventsourcing.sample

import com.cultureamp.eventsourcing.*
import java.util.*

data class CreateClassicPizza(
    override val aggregateId: UUID,
    val baseStyle: PizzaStyle
) : PizzaCreationCommand()

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

sealed class PizzaCreationCommand : PizzaCommand(),
    CreationCommand

sealed class PizzaUpdateCommand : PizzaCommand(), UpdateCommand

data class AddTopping(override val aggregateId: UUID, val topping: PizzaTopping) : PizzaUpdateCommand()
data class EatPizza(override val aggregateId: UUID) : PizzaUpdateCommand()

sealed class PizzaEvent : DomainEvent

sealed class PizzaCreationEvent : PizzaEvent(), CreationEvent
data class PizzaCreated(val baseStyle: PizzaStyle, val initialToppings: List<PizzaTopping>) : PizzaCreationEvent()

sealed class PizzaUpdateEvent : PizzaEvent(), UpdateEvent
data class PizzaToppingAdded(val newTopping: PizzaTopping) : PizzaUpdateEvent()

class PizzaEaten : PizzaUpdateEvent()

sealed class PizzaError : CommandError
class ToppingAlreadyPresent : PizzaError()
class PizzaAlreadyEaten : PizzaError()

data class PizzaAggregate(
    val baseStyle: PizzaStyle, val toppings: List<PizzaTopping>, val isEaten: Boolean = false
) : TypedAggregate<PizzaUpdateCommand, PizzaUpdateEvent, PizzaError, PizzaAggregate> {
    constructor(event: PizzaCreated) : this(baseStyle = event.baseStyle, toppings = event.initialToppings)

    override fun updated(event: PizzaUpdateEvent): PizzaAggregate = when (event) {
        is PizzaToppingAdded -> this.copy(toppings = this.toppings + event.newTopping)
        is PizzaEaten -> this.copy(isEaten = true)
    }

    companion object :
        AggregateConstructor<PizzaCreationCommand, PizzaCreationEvent, PizzaError, PizzaUpdateCommand, PizzaUpdateEvent, PizzaAggregate> {
        override fun created(event: PizzaCreationEvent): PizzaAggregate = when (event) {
            is PizzaCreated -> PizzaAggregate(event)
        }

        private fun classicToppings(style: PizzaStyle): List<PizzaTopping> = when (style) {
            PizzaStyle.MARGHERITA -> listOf(PizzaTopping.CHEESE, PizzaTopping.TOMATO_PASTE, PizzaTopping.BASIL)
            PizzaStyle.HAWAIIAN -> listOf(
                PizzaTopping.CHEESE,
                PizzaTopping.TOMATO_PASTE,
                PizzaTopping.HAM,
                PizzaTopping.PINEAPPLE
            )
        }


        override fun create(command: PizzaCreationCommand): Either<PizzaError, PizzaCreated> = when (command) {
            is CreateClassicPizza -> {
                val initialToppings = classicToppings(command.baseStyle)
                Right(
                    PizzaCreated(
                        command.baseStyle,
                        initialToppings
                    )
                )
            }
        }
    }

    override fun update(command: PizzaUpdateCommand): Either<PizzaError, List<PizzaUpdateEvent>> {
        return when (isEaten) {
            true -> Left(PizzaAlreadyEaten())
            false -> when (command) {
                is AddTopping -> when (toppings.contains(command.topping)) {
                    true -> Left(ToppingAlreadyPresent())
                    false -> Right(listOf(PizzaToppingAdded(command.topping)))
                }
                is EatPizza -> Right(listOf(PizzaEaten()))
            }
        }
    }
}
