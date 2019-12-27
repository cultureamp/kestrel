package survey.demo

import eventsourcing.CommandGateway
import eventsourcing.ReadWriteDatabase
import eventsourcing.Right
import eventsourcing.Updated
import java.util.UUID
import java.util.Date

class PaymentSagaReactor(
    private val commandGateway: CommandGateway,
    private val paymentService: PaymentService,
    private val emailService: EmailService,
    private val readWriteDatabase: ReadWriteDatabase
    ) {
    fun react(event: PaymentSagaEvent, aggregateId: UUID) = when (event) {
        is PaymentSagaStarted -> with(event) {
            readWriteDatabase.upsert(aggregateId, PaymentRow(fromUserId))
            paymentService.pay(fromUserId, toUserBankDetails, dollarAmount)
            commandGateway.dispatch(StartThirdPartyPayment(aggregateId, Date()))
        }
        is StartedThirdPartyPayment -> Right(Updated)
        is FinishedThirdPartyPayment -> {
            val payment = readWriteDatabase.find(PaymentRow::class, aggregateId)!!
            val message = "successfully paid"
            emailService.notify(payment.fromUserId, message)
            commandGateway.dispatch(StartThirdPartyEmailNotification(aggregateId, message, Date()))
        }
        is FailedThirdPartyPayment -> {
            val payment = readWriteDatabase.find(PaymentRow::class, aggregateId)!!
            val message = "payment failed"
            emailService.notify(payment.fromUserId, message)
            commandGateway.dispatch(StartThirdPartyEmailNotification(aggregateId, message, Date()))
        }
        is StartedThirdPartyEmailNotification -> Right(Updated)
        is FinishedThirdPartyEmailNotification -> Right(Updated)
        is FailedThirdPartyEmailNotification -> Right(Updated) // or retry?
    }
}

data class PaymentRow(val fromUserId: UUID)