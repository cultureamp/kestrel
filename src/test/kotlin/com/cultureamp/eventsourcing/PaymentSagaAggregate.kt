package com.cultureamp.eventsourcing

import arrow.core.Either
import arrow.core.right
import com.cultureamp.eventsourcing.sample.StandardEventMetadata
import org.joda.time.DateTime
import java.util.*

object PaymentSagaAggregate {
    fun create(command: StartPaymentSaga, metadata: StandardEventMetadata): Either<DomainError, PaymentSagaStarted> = with(command) {
        PaymentSagaStarted(fromUserId, toUserBankDetails, dollarAmount, DateTime()).right()
    }

    fun update(command: PaymentSagaUpdateCommand, metadata: StandardEventMetadata): Either<DomainError, List<PaymentSagaUpdateEvent>> = when (command) {
        is StartThirdPartyPayment -> listOf(StartedThirdPartyPayment(command.startedAt)).right()
        is RegisterThirdPartySuccess -> listOf(FinishedThirdPartyPayment(DateTime())).right()
        is RegisterThirdPartyFailure -> listOf(FailedThirdPartyPayment(DateTime())).right()
        is StartThirdPartyEmailNotification -> listOf(StartedThirdPartyEmailNotification(command.message, command.startedAt)).right()
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
