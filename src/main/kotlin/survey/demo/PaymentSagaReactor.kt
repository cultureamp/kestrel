package survey.demo

import com.cultureamp.eventsourcing.CommandGateway
import com.cultureamp.eventsourcing.Right
import com.cultureamp.eventsourcing.Updated
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.joda.time.DateTime
import java.util.UUID

class PaymentSagaReactor(
    private val commandGateway: CommandGateway,
    private val paymentService: PaymentService,
    private val emailService: EmailService,
    private val database: Database
    ) {
    fun setup() {
        transaction(database) {
            SchemaUtils.create(Payments);
        }
    }
    fun react(event: PaymentSagaEvent, aggregateId: UUID) = when (event) {
        is PaymentSagaStarted -> with(event) {
            transaction(database) {
                Payments.insert {
                    it[sagaId] = aggregateId
                    it[fromUserId] = event.fromUserId
                    it[toUserBankDetails] = event.toUserBankDetails
                    it[dollarAmount] = event.dollarAmount
                }
            }
            paymentService.pay(fromUserId, toUserBankDetails, dollarAmount)
            commandGateway.dispatch(StartThirdPartyPayment(aggregateId, DateTime()))
        }
        is StartedThirdPartyPayment -> Right(Updated)
        is FinishedThirdPartyPayment -> {
            val fromUserId = transaction(database) {
                Payments.update({ Payments.sagaId eq aggregateId }) {
                    it[succeeded] = true
                }
                Payments
                    .slice(Payments.fromUserId)
                    .select { Payments.sagaId eq aggregateId }
                    .limit(1)
                    .map { it[Payments.fromUserId] }
                    .first()
            }

            val message = "successfully paid"
            emailService.notify(fromUserId, message)
            commandGateway.dispatch(StartThirdPartyEmailNotification(aggregateId, message, DateTime()))
        }
        is FailedThirdPartyPayment -> {
            val fromUserId = transaction(database) {
                Payments.update({ Payments.sagaId eq aggregateId }) {
                    it[succeeded] = false
                }
                Payments
                    .slice(Payments.fromUserId)
                    .select { Payments.sagaId eq aggregateId }
                    .limit(1)
                    .map { it[Payments.fromUserId] }
                    .first()
            }
            val message = "payment failed"
            emailService.notify(fromUserId, message)
            commandGateway.dispatch(StartThirdPartyEmailNotification(aggregateId, message, DateTime()))
        }
        is StartedThirdPartyEmailNotification -> Right(Updated)
        is FinishedThirdPartyEmailNotification -> Right(Updated)
        is FailedThirdPartyEmailNotification -> Right(Updated) // or retry?
    }
}

object Payments : Table() {
    val sagaId = uuid("saga_id")
    val fromUserId = uuid("from_user_id")
    val toUserBankDetails = text("to_user_bank_details")
    val dollarAmount = integer("dollar_amount")
    val succeeded = bool("succeeded").nullable()
}
