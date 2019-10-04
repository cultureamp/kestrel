package survey.demo

import eventsourcing.*
import java.util.*

data class PaymentSagaAggregate(override val aggregateId: UUID) : Aggregate {
    constructor(event: PaymentSagaStarted) : this(event.aggregateId)

    companion object {
        fun create(command: StartPaymentSaga): Either<CommandError, PaymentSagaStarted> = with(command) {
            Right(PaymentSagaStarted(aggregateId, fromUserId, toUserBankDetails, dollarAmount, Date()))
        }
    }

    fun updated(event: PaymentSagaUpdateEvent) = this

    fun update(command: PaymentSagaUpdateCommand): Either<CommandError, List<PaymentSagaUpdateEvent>> = when (command) {
        is StartThirdPartyPayment -> Right.list(StartedThirdPartyPayment(aggregateId, command.startedAt))
        is RegisterThirdPartySuccess -> Right.list(FinishedThirdPartyPayment(aggregateId, Date()))
        is RegisterThirdPartyFailure -> Right.list(FailedThirdPartyPayment(aggregateId, Date()))
        is StartThirdPartyEmailNotification -> TODO()
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
