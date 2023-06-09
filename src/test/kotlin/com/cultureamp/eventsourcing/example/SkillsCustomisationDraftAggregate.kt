package com.cultureamp.eventsourcing.example

import com.cultureamp.eventsourcing.AlreadyActionedCommandError
import com.cultureamp.eventsourcing.CreationCommand
import com.cultureamp.eventsourcing.CreationEvent
import com.cultureamp.eventsourcing.DomainError
import com.cultureamp.eventsourcing.DomainEvent
import com.cultureamp.eventsourcing.Either
import com.cultureamp.eventsourcing.Left
import com.cultureamp.eventsourcing.Right
import com.cultureamp.eventsourcing.UpdateCommand
import com.cultureamp.eventsourcing.UpdateEvent
import com.cultureamp.eventsourcing.example.DraftStatus.Discarded
import com.cultureamp.eventsourcing.example.DraftStatus.Open
import com.cultureamp.eventsourcing.example.DraftStatus.Published
import com.cultureamp.eventsourcing.map
import com.cultureamp.eventsourcing.sample.StandardEventMetadata
import org.joda.time.DateTime
import java.util.UUID

sealed interface SkillsCustomizationDraftCreationCommand : CreationCommand
sealed interface SkillsCustomizationDraftUpdateCommand : UpdateCommand

data class DraftAddSkill(override val aggregateId: UUID, val skillId: UUID, val label: String?, val description: String?) : SkillsCustomizationDraftCreationCommand, SkillsCustomizationDraftUpdateCommand
data class DraftRemoveSkill(override val aggregateId: UUID, val skillId: UUID) : SkillsCustomizationDraftCreationCommand, SkillsCustomizationDraftUpdateCommand

data class DraftPublish(override val aggregateId: UUID) : SkillsCustomizationDraftUpdateCommand
data class DraftDiscard(override val aggregateId: UUID) : SkillsCustomizationDraftUpdateCommand

sealed interface SkillsCustomizationDraftEvent : DomainEvent
sealed interface SkillsCustomizationDraftCreationEvent : SkillsCustomizationDraftEvent, CreationEvent {
    val accountId: UUID
}

sealed interface SkillsCustomizationDraftUpdateEvent : SkillsCustomizationDraftEvent, UpdateEvent

data class DraftSnapshottedCanonicalSkills(
    val coreSkills: List<CoreSkillSnapshot>,
    val coreLeadershipSkills: List<CoreLeadershipSkillSnapshot>,
    val coreSkillGroupId: UUID,
    val coreLeadershipSkillGroupId: UUID,
    override val accountId: UUID
) : SkillsCustomizationDraftCreationEvent

data class DraftSkillAdded(val skillId: UUID, val label: String?, val description: String?, override val accountId: UUID, val addedAt: DateTime) : SkillsCustomizationDraftCreationEvent, SkillsCustomizationDraftUpdateEvent
data class DraftSkillRemoved(val skillId: UUID, override val accountId: UUID, val removedAt: DateTime) : SkillsCustomizationDraftCreationEvent, SkillsCustomizationDraftUpdateEvent

data class DraftDiscarded(val discardedAt: DateTime) : SkillsCustomizationDraftUpdateEvent
data class DraftPublished(val publishedAt: DateTime) : SkillsCustomizationDraftUpdateEvent

data class CoreSkillSnapshot(val coreSkill: CoreSkill, val label: String, val description: String, val skillId: UUID)
data class CoreLeadershipSkillSnapshot(val coreLeadershipSkill: CoreLeadershipSkill, val label: String, val description: String, val skillId: UUID)

interface PublishedSkillsProjection {
    fun publishedSkillsFor(accountId: UUID): Map<UUID, Skill>
}

interface AdminProjection {
    fun isAdminOfAccount(userId: UUID, accountId: UUID): Boolean
}

object AlwaysAdminAdminProjection : AdminProjection {
    override fun isAdminOfAccount(userId: UUID, accountId: UUID) = true
}

data class SkillsCustomisationDraftAggregate(
    val status: DraftStatus = Open,
    val removals: List<UUID> = emptyList(),
    val additions: Map<UUID, Skill> = emptyMap(),
) {
    companion object {
        fun created(event: SkillsCustomizationDraftCreationEvent) = when (event) {
            is DraftSkillAdded -> SkillsCustomisationDraftAggregate(additions = mapOf(event.skillId to Skill(event.skillId, event.label, event.description)))
            is DraftSkillRemoved -> SkillsCustomisationDraftAggregate(removals = listOf(event.skillId))
            is DraftSnapshottedCanonicalSkills -> {
                val coreSkills = event.coreSkills.map { it.skillId to Skill(it.skillId, it.label, it.description) }
                val coreLeadershipSkills = event.coreLeadershipSkills.map { it.skillId to Skill(it.skillId, it.label, it.description) }
                SkillsCustomisationDraftAggregate(additions = (coreSkills + coreLeadershipSkills).toMap())
            }
        }

        fun create(
            publishedSkillsProjection: PublishedSkillsProjection,
            adminProjection: AdminProjection,
            command: SkillsCustomizationDraftCreationCommand,
            metadata: StandardEventMetadata
        ): Either<DomainError, Pair<SkillsCustomizationDraftCreationEvent, List<SkillsCustomizationDraftUpdateEvent>>> {
            if (metadata.executorId != null && !adminProjection.isAdminOfAccount(userId = metadata.executorId, accountId = metadata.accountId)) return Left(OnlyAdminsCanCustomiseSkills)
            val publishedState = publishedSkillsProjection.publishedSkillsFor(metadata.accountId)

            val firstTimeSnapshotEvent: DraftSnapshottedCanonicalSkills? = if (publishedState.isNotEmpty()) null else {
                val coreSkills = CoreSkill.values().map { CoreSkillSnapshot(it, it.label, it.description, it.deterministicSkillIdFor(accountId = metadata.accountId)) }
                val coreLeadershipSkills = CoreLeadershipSkill.values().map { CoreLeadershipSkillSnapshot(it, it.label, it.description, it.deterministicSkillIdFor(accountId = metadata.accountId)) }
                val coreSkillGroupId = CoreSkill.deterministicSkillGroupIdFor(metadata.accountId)
                val coreLeadershipSkillGroupId = CoreLeadershipSkill.deterministicSkillGroupIdFor(metadata.accountId)
                DraftSnapshottedCanonicalSkills(coreSkills, coreLeadershipSkills, coreSkillGroupId, coreLeadershipSkillGroupId, metadata.accountId)
            }
            val aggregate = firstTimeSnapshotEvent?.let { created(it) } ?: SkillsCustomisationDraftAggregate()
            val updateEvent = when (command) {
                is DraftAddSkill -> aggregate.addSkill(command, publishedState, metadata.accountId)
                is DraftRemoveSkill -> aggregate.removeSkill(command, publishedState, metadata.accountId)
            }
            return if (firstTimeSnapshotEvent != null) updateEvent.map { firstTimeSnapshotEvent to listOf(it) } else updateEvent.map { it to emptyList() }
        }
    }

    fun updated(event: SkillsCustomizationDraftUpdateEvent): SkillsCustomisationDraftAggregate = when (event) {
        is DraftDiscarded -> this.copy(status = Discarded)
        is DraftPublished -> this.copy(status = Published)
        is DraftSkillAdded -> this.copy(additions = additions + (event.skillId to Skill(event.skillId, event.label, event.description)))
        is DraftSkillRemoved -> {
            if (additions.containsKey(event.skillId)) {
                this.copy(additions = additions - event.skillId)
            } else {
                this.copy(removals = removals + event.skillId)
            }
        }
    }

    fun update(
        publishedSkillsProjection: PublishedSkillsProjection,
        adminProjection: AdminProjection,
        command: SkillsCustomizationDraftUpdateCommand,
        metadata: StandardEventMetadata
    ): Either<DomainError, List<SkillsCustomizationDraftUpdateEvent>> {
        if (metadata.executorId != null && !adminProjection.isAdminOfAccount(userId = metadata.executorId, accountId = metadata.accountId)) return Left(OnlyAdminsCanCustomiseSkills)
        if (status != Open) return Left(OnlyOpenDraftsMayBeEditted)
        val currentState = currentState(publishedSkillsProjection.publishedSkillsFor(metadata.accountId))
        return when (command) {
            is DraftAddSkill -> addSkill(command, currentState, metadata.accountId).map { listOf(it) }
            is DraftRemoveSkill -> removeSkill(command, currentState, metadata.accountId).map { listOf(it) }
            is DraftDiscard -> if (status == Discarded) Left(AlreadyDiscarded) else Right.list(DraftDiscarded(DateTime.now()))
            is DraftPublish -> if (status == Published) Left(AlreadyPublished) else Right.list(DraftPublished(DateTime.now()))
        }
    }

    private fun addSkill(command: DraftAddSkill, currentState: Map<UUID, Skill>, accountId: UUID): Either<DomainError, DraftSkillAdded> = if (currentState.containsKey(command.skillId)) {
        Left(SkillAlreadyAdded)
    } else {
        Right(DraftSkillAdded(skillId = command.skillId, label = command.label, description = command.description, accountId = accountId, addedAt = DateTime.now()))
    }

    private fun removeSkill(command: DraftRemoveSkill, currentState: Map<UUID, Skill>, accountId: UUID): Either<DomainError, DraftSkillRemoved> = if (currentState.containsKey(command.skillId)) {
        Right(DraftSkillRemoved(skillId = command.skillId, accountId = accountId, removedAt = DateTime.now()))
    } else {
        Left(SkillNotPresentToRemove)
    }

    private fun currentState(publishedSkills: Map<UUID, Skill>): Map<UUID, Skill> {
        return publishedSkills - removals + additions
    }
}

enum class DraftStatus { Open, Discarded, Published }
object OnlyOpenDraftsMayBeEditted : DomainError
object OnlyAdminsCanCustomiseSkills : DomainError
object SkillAlreadyAdded : AlreadyActionedCommandError
object SkillNotPresentToRemove : AlreadyActionedCommandError
object AlreadyDiscarded : AlreadyActionedCommandError
object AlreadyPublished : AlreadyActionedCommandError

data class Skill(val skillId: UUID, val label: String?, val description: String?)

enum class CoreSkill(val label: String, val description: String) {
    Approachability("Approachability", "Personable. Being easy to approach and talk to. Developing strong interpersonal relationships"),
    Authenticity("Authenticity", "Open and transparent. Willing to admit mistakes and shortcomings. Showing self-awareness"),
    Availability("Availability", "Being accessible to people who need or are relying on me. Responsive in communications");

    fun deterministicSkillIdFor(accountId: UUID): UUID {
        return UUIDType5.nameUUIDFromNamespaceAndString(SKILL_NAMESPACE_UUID, "CoreSkill-$accountId-$name")
    }

    companion object {
        fun deterministicSkillGroupIdFor(accountId: UUID): UUID {
            return UUIDType5.nameUUIDFromNamespaceAndString(SKILL_GROUP_NAMESPACE_UUID, "CoreSkill-$accountId")
        }
    }
}

val SKILL_GROUP_NAMESPACE_UUID = UUID.nameUUIDFromBytes("SkillGroupAggregate".toByteArray())
val SKILL_NAMESPACE_UUID = UUID.nameUUIDFromBytes("SkillAggregate".toByteArray())

enum class CoreLeadershipSkill(val label: String, val description: String) {
    BusinessFocus("Business Focus", "Strong focus on business outcomes. Clear strategies to achieve them and maintain accountability"),
    ChangeLeadership("Change Leadership", "Driving necessary change within our company while minimizing friction. Guiding through uncertainty"),
    DecisionMaking("Decision Making", "Making well-informed yet timely decisions. Open to considering new information and perspectives");

    fun deterministicSkillIdFor(accountId: UUID): UUID {
        return UUIDType5.nameUUIDFromNamespaceAndString(SKILL_NAMESPACE_UUID, "CoreLeadershipSkill-$accountId-$name")
    }

    companion object {
        fun deterministicSkillGroupIdFor(accountId: UUID): UUID {
            return UUIDType5.nameUUIDFromNamespaceAndString(SKILL_GROUP_NAMESPACE_UUID, "CoreLeadershipSkill-$accountId")
        }
    }
}
