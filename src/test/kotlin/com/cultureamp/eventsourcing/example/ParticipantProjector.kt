package com.cultureamp.eventsourcing.example

import com.cultureamp.eventsourcing.DomainEventProcessor
import com.cultureamp.eventsourcing.example.ParticipantTable.invitationId
import com.cultureamp.eventsourcing.example.ParticipantTable.invitedAt
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jodatime.datetime
import java.util.*


class ParticipantProjector(private val database: Database): DomainEventProcessor<ParticipantEvent> {
    override fun process(event: ParticipantEvent, aggregateId: UUID): Unit = transaction(database) {
        when (event) {
            is Invited -> ParticipantTable.insert {
                it[invitationId] = aggregateId
                it[surveyPeriodId] = event.surveyPeriodId
                it[employeeId] = event.employeeId
                it[invitedAt] = event.invitedAt
            }
            is Uninvited -> ParticipantTable.update({ invitationId eq aggregateId }) {
                it[invitedAt] = null
            }
            is Reinvited -> ParticipantTable.update({ invitationId eq aggregateId }) {
                it[invitedAt] = event.reinvitedAt
            }
            is Rereinvited -> throw RuntimeException("Projector only supports upcasted events")
        }
    }

    fun isInvited(invitationId: UUID): Boolean = transaction(database) {
        ParticipantTable.selectAll().where { (ParticipantTable.invitationId eq invitationId) and (ParticipantTable.invitedAt neq null) }.firstOrNull() != null
    }

    init {
        transaction(database) {
            SchemaUtils.create(ParticipantTable)
        }
    }

}

object ParticipantTable : Table() {
    val invitationId = javaUUID("invitation_id")
    val surveyPeriodId = javaUUID("account_id")
    val employeeId = javaUUID("employee_id")
    val invitedAt = datetime("invited_at").nullable()
    override val primaryKey = PrimaryKey(invitationId)
}