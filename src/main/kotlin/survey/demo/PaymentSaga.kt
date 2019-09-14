package survey.demo

import eventsourcing.*
import survey.design.*
import survey.design.Create
import survey.design.Locale
import java.util.*

data class PaymentSaga(override val aggregateId: UUID, val startEvent: PaymentSagaStarted, val updateEvents: List<PaymentSagaUpdateEvent> = emptyList()) : Aggregate {
    constructor(event: PaymentSagaStarted) : this(event.aggregateId, event)

    companion object {
        fun create(command: StartPaymentSaga): Either<CommandError, PaymentSagaStarted> = with(command) {
            Right(PaymentSagaStarted(aggregateId, fromUserId, toUserBankDetails, dollarAmount, Date()))
        }
    }

    fun updated(event: PaymentSagaUpdateEvent): PaymentSaga {
        return this.copy(updateEvents = updateEvents + event)
    }

    fun update(command: PaymentSagaUpdateCommand): Either<CommandError, List<PaymentSagaUpdateEvent>> = when (command) {
        is RegisterThirdPartySuccess -> Right.list(FinishedThirdPartyPayment(aggregateId, Date()))
        is RegisterThirdPartyFailure -> Right.list(FailedThirdPartyPayment(aggregateId, Date()))
    }

    fun step(paymentService: PaymentService, emailService: EmailService, commandGateway: CommandGateway): Either<CommandError, List<PaymentSagaUpdateEvent>> {
        val lastEvent = updateEvents.lastOrNull() ?: startEvent
        return when (lastEvent) {
            is PaymentSagaStarted -> {
                Right.list(StartedThirdPartyPayment(aggregateId, Date()))
            }
            is StartedThirdPartyPayment -> {
                paymentService.pay(startEvent.fromUserId, startEvent.toUserBankDetails, startEvent.dollarAmount)
                Right.list()
            }
            is FinishedThirdPartyPayment -> {
                Right.list(StartedThirdPartyEmailNotification(aggregateId, "successfully paid", Date()))
            }
            is FailedThirdPartyPayment -> {
                Right.list(StartedThirdPartyEmailNotification(aggregateId, "failed to pay", Date()))
            }
            is StartedThirdPartyEmailNotification -> {
                emailService.notify(startEvent.fromUserId, lastEvent.message)
                Right.list()
            }
            is FinishedThirdPartyEmailNotification -> Right.list()
            is FailedThirdPartyEmailNotification -> Right.list() // or retry?
        }
    }
}

data class StartPaymentSaga(
    override val aggregateId: UUID,
    val fromUserId: UUID,
    val toUserBankDetails: String,
    val dollarAmount: Int
) : CreationCommand

sealed class PaymentSagaUpdateCommand : UpdateCommand
data class RegisterThirdPartySuccess(override val aggregateId: UUID) : PaymentSagaUpdateCommand()
data class RegisterThirdPartyFailure(override val aggregateId: UUID) : PaymentSagaUpdateCommand()

sealed class PaymentSagaEvent : Event
data class PaymentSagaStarted(
    override val aggregateId: UUID,
    val fromUserId: UUID,
    val toUserBankDetails: String,
    val dollarAmount: Int,
    val startedAt: Date
) : PaymentSagaEvent(), CreationEvent

sealed class PaymentSagaUpdateEvent : PaymentSagaEvent(), UpdateEvent

data class StartedThirdPartyPayment(override val aggregateId: UUID, val startedAt: Date) : PaymentSagaUpdateEvent()
data class FinishedThirdPartyPayment(override val aggregateId: UUID, val finishedAt: Date) : PaymentSagaUpdateEvent()
data class FailedThirdPartyPayment(override val aggregateId: UUID, val failedAt: Date) : PaymentSagaUpdateEvent()

data class StartedThirdPartyEmailNotification(override val aggregateId: UUID, val message: String, val startedAt: Date) : PaymentSagaUpdateEvent()
data class FinishedThirdPartyEmailNotification(override val aggregateId: UUID, val finishedAt: Date) : PaymentSagaUpdateEvent()
data class FailedThirdPartyEmailNotification(override val aggregateId: UUID, val error: CommandError, val failedAt: Date) : PaymentSagaUpdateEvent()
