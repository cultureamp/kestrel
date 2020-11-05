package com.cultureamp.eventsourcing

import org.joda.time.DateTime
import java.util.*

object PaymentSagaAggregate {
    fun create(command: StartPaymentSaga): Result<DomainError, PaymentSagaStarted> = with(command) {
        Success(PaymentSagaStarted(fromUserId, toUserBankDetails, dollarAmount, DateTime()))
    }

    fun update(command: PaymentSagaUpdateCommand): Result<DomainError, List<PaymentSagaUpdateEvent>> = when (command) {
        is StartThirdPartyPayment -> Success.list(StartedThirdPartyPayment(command.startedAt))
        is RegisterThirdPartySuccess -> Success.list(FinishedThirdPartyPayment(DateTime()))
        is RegisterThirdPartyFailure -> Success.list(FailedThirdPartyPayment(DateTime()))
        is StartThirdPartyEmailNotification -> Success.list(StartedThirdPartyEmailNotification(command.message, command.startedAt))
    }
}

sealed class PaymentSagaCommand : Command<DomainError>

data class StartPaymentSaga(
    override val aggregateId: UUID,
    val fromUserId: UUID,
    val toUserBankDetails: String,
    val dollarAmount: Int
) : PaymentSagaCommand(), CreationCommand<DomainError>

sealed class PaymentSagaUpdateCommand : PaymentSagaCommand(), UpdateCommand<DomainError>
data class StartThirdPartyPayment(override val aggregateId: UUID, val startedAt: DateTime) : PaymentSagaUpdateCommand()
data class StartThirdPartyEmailNotification(override val aggregateId: UUID, val message: String, val startedAt: DateTime) : PaymentSagaUpdateCommand()
data class RegisterThirdPartySuccess(override val aggregateId: UUID) : PaymentSagaUpdateCommand()
data class RegisterThirdPartyFailure(override val aggregateId: UUID) : PaymentSagaUpdateCommand()

sealed class PaymentSagaEvent : DomainEvent
data class PaymentSagaStarted(
    val fromUserId: UUID,
    val toUserBankDetails: String,
    val dollarAmount: Int,
    val startedAt: DateTime
) : PaymentSagaEvent(), CreationEvent

sealed class PaymentSagaUpdateEvent : PaymentSagaEvent(), UpdateEvent

data class StartedThirdPartyPayment(val startedAt: DateTime) : PaymentSagaUpdateEvent()
data class FinishedThirdPartyPayment(val finishedAt: DateTime) : PaymentSagaUpdateEvent()
data class FailedThirdPartyPayment(val failedAt: DateTime) : PaymentSagaUpdateEvent()

data class StartedThirdPartyEmailNotification(val message: String, val startedAt: DateTime) : PaymentSagaUpdateEvent()
data class FinishedThirdPartyEmailNotification(val finishedAt: DateTime) : PaymentSagaUpdateEvent()
data class FailedThirdPartyEmailNotification(val error: CommandError, val failedAt: DateTime) : PaymentSagaUpdateEvent()
