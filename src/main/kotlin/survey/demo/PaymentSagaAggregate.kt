package survey.demo

import eventsourcing.*
import java.util.*

object PaymentSagaAggregate : Aggregate {
    fun create(command: StartPaymentSaga): Either<CommandError, PaymentSagaStarted> = with(command) {
        Right(PaymentSagaStarted(fromUserId, toUserBankDetails, dollarAmount, Date()))
    }

    fun update(command: PaymentSagaUpdateCommand): Either<CommandError, List<PaymentSagaUpdateEvent>> = when (command) {
        is StartThirdPartyPayment -> Right.list(StartedThirdPartyPayment(command.startedAt))
        is RegisterThirdPartySuccess -> Right.list(FinishedThirdPartyPayment(Date()))
        is RegisterThirdPartyFailure -> Right.list(FailedThirdPartyPayment(Date()))
        is StartThirdPartyEmailNotification -> Right.list(StartedThirdPartyEmailNotification(command.message, command.startedAt))
    }
}

sealed class PaymentSagaCommand : Command

data class StartPaymentSaga(
    override val aggregateId: UUID,
    val fromUserId: UUID,
    val toUserBankDetails: String,
    val dollarAmount: Int
) : PaymentSagaCommand(), CreationCommand

sealed class PaymentSagaUpdateCommand : PaymentSagaCommand(), UpdateCommand
data class StartThirdPartyPayment(override val aggregateId: UUID, val startedAt: Date) : PaymentSagaUpdateCommand()
data class StartThirdPartyEmailNotification(override val aggregateId: UUID, val message: String, val startedAt: Date) : PaymentSagaUpdateCommand()
data class RegisterThirdPartySuccess(override val aggregateId: UUID) : PaymentSagaUpdateCommand()
data class RegisterThirdPartyFailure(override val aggregateId: UUID) : PaymentSagaUpdateCommand()

sealed class PaymentSagaEvent : DomainEvent
data class PaymentSagaStarted(
    val fromUserId: UUID,
    val toUserBankDetails: String,
    val dollarAmount: Int,
    val startedAt: Date
) : PaymentSagaEvent(), CreationEvent

sealed class PaymentSagaUpdateEvent : PaymentSagaEvent(), UpdateEvent

data class StartedThirdPartyPayment(val startedAt: Date) : PaymentSagaUpdateEvent()
data class FinishedThirdPartyPayment(val finishedAt: Date) : PaymentSagaUpdateEvent()
data class FailedThirdPartyPayment(val failedAt: Date) : PaymentSagaUpdateEvent()

data class StartedThirdPartyEmailNotification(val message: String, val startedAt: Date) : PaymentSagaUpdateEvent()
data class FinishedThirdPartyEmailNotification(val finishedAt: Date) : PaymentSagaUpdateEvent()
data class FailedThirdPartyEmailNotification(val error: CommandError, val failedAt: Date) : PaymentSagaUpdateEvent()
