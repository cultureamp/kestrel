package com.cultureamp.eventsourcing.sample

import com.cultureamp.eventsourcing.Command
import com.cultureamp.eventsourcing.CreationCommand
import com.cultureamp.eventsourcing.CreationEvent
import com.cultureamp.eventsourcing.DomainError
import com.cultureamp.eventsourcing.DomainEvent
import com.cultureamp.eventsourcing.Either
import com.cultureamp.eventsourcing.EventMetadata
import com.cultureamp.eventsourcing.Left
import com.cultureamp.eventsourcing.Right
import com.cultureamp.eventsourcing.UpdateCommand
import com.cultureamp.eventsourcing.UpdateEvent
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonInclude.Include
import java.util.*


abstract class RequiredMetadata: EventMetadata() {
    abstract val executorId: String
}

@JsonInclude(Include.NON_NULL) // don't want to store optional fields we don't need on all classes
data class StandardEventMetadata(override val executorId: String, val restaurantId: String? = null): RequiredMetadata()

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

sealed class PizzaCreationCommand : PizzaCommand(), CreationCommand

sealed class PizzaUpdateCommand : PizzaCommand(), UpdateCommand

data class AddTopping(override val aggregateId: UUID, val topping: PizzaTopping) : PizzaUpdateCommand()
data class EatPizza(override val aggregateId: UUID) : PizzaUpdateCommand()

sealed class PizzaEvent : DomainEvent

sealed class PizzaCreationEvent : PizzaEvent(), CreationEvent
data class PizzaCreated(val baseStyle: PizzaStyle, val initialToppings: List<PizzaTopping>) : PizzaCreationEvent()

sealed class PizzaUpdateEvent : PizzaEvent(), UpdateEvent
data class PizzaToppingAdded(val newTopping: PizzaTopping) : PizzaUpdateEvent()

// not necessarily the best way to handle no-data events, but
// works for now – we can't use a bare singleton
// as it gets deserialised as a different reference:
// https://github.com/FasterXML/jackson-module-kotlin/issues/141
object PizzaEaten : PizzaUpdateEvent() {
    override fun equals(other: Any?) = other?.javaClass == javaClass
    override fun hashCode(): Int = javaClass.hashCode()
    operator fun invoke() = PizzaEaten
}

sealed class PizzaError : DomainError
class ToppingAlreadyPresent : PizzaError()
class PizzaAlreadyEaten : PizzaError()

data class PizzaAggregate(val baseStyle: PizzaStyle, val toppings: List<PizzaTopping>, val isEaten: Boolean = false) {
    constructor(event: PizzaCreated) : this(baseStyle = event.baseStyle, toppings = event.initialToppings)

    fun aggregateType() = "pizza"

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
                Right(
                    PizzaCreated(
                        command.baseStyle,
                        initialToppings
                    )
                )
            }
        }
    }

    fun update(command: PizzaUpdateCommand): Either<PizzaError, List<PizzaUpdateEvent>> {
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
