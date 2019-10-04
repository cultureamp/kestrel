package survey.demo

import eventsourcing.CommandGateway
import eventsourcing.ReadWriteDatabase
import eventsourcing.Right
import eventsourcing.Updated
import java.util.*

class PaymentSagaReactor(
    private val commandGateway: CommandGateway,
    private val paymentService: PaymentService,
    private val emailService: EmailService,
    private val readWriteDatabase: ReadWriteDatabase
    ) {
    fun react(event: PaymentSagaEvent) = when (event) {
        is PaymentSagaStarted -> with(event) {
            readWriteDatabase.insert(aggregateId, PaymentRow(fromUserId))
            paymentService.pay(fromUserId, toUserBankDetails, dollarAmount)
            commandGateway.dispatch(StartThirdPartyPayment(event.aggregateId, Date()))
        }
        is StartedThirdPartyPayment -> Right(Updated)
        is FinishedThirdPartyPayment -> {
            val payment = readWriteDatabase.find(PaymentRow::class, event.aggregateId)
            val message = "successfully paid"
            emailService.notify(payment.fromUserId, message)
            commandGateway.dispatch(StartThirdPartyEmailNotification(event.aggregateId, message, Date()))
        }
        is FailedThirdPartyPayment -> {
            val payment = readWriteDatabase.find(PaymentRow::class, event.aggregateId)
            val message = "payment failed"
            emailService.notify(payment.fromUserId, message)
            commandGateway.dispatch(StartThirdPartyEmailNotification(event.aggregateId, message, Date()))
        }
        is StartedThirdPartyEmailNotification -> Right(Updated)
        is FinishedThirdPartyEmailNotification -> Right(Updated)
        is FailedThirdPartyEmailNotification -> Right(Updated) // or retry?
    }
}

data class PaymentRow(val fromUserId: UUID)