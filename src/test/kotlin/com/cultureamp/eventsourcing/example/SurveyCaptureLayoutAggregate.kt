package com.cultureamp.eventsourcing.example

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.cultureamp.eventsourcing.*
import org.joda.time.DateTime
import com.cultureamp.eventsourcing.example.DemographicSectionPosition.bottom
import com.cultureamp.eventsourcing.example.DemographicSectionPosition.top
import com.cultureamp.eventsourcing.sample.StandardEventMetadata
import java.util.UUID


data class SurveyCaptureLayoutAggregate(
    val sectionsForIntendedPurpose: Map<IntendedPurpose, List<Section>> = emptyMap(),
    val demographicSectionsPlacement: DemographicSectionPosition = bottom,
    val questions: Set<UUID> = emptySet()
) {
    companion object {
        fun created(event: SurveyCaptureLayoutCreationEvent) = when (event) {
            is Generated -> SurveyCaptureLayoutAggregate()
            is Snapshot -> with(event) { SurveyCaptureLayoutAggregate(sectionsForIntendedPurpose, demographicSectionsPlacement, questions) }
        }

        fun create(command: SurveyCaptureLayoutCreationCommand): Either<SurveyCaptureLayoutCommandError, Generated> = when (command) {
            is Generate -> with(command) { Generated(surveyId, generatedAt).right() }
        }
    }

    fun updated(event: SurveyCaptureLayoutUpdateEvent): SurveyCaptureLayoutAggregate = when (event) {
        is SectionAdded -> with(event) {
            val section = Section(sectionId, name.toMap(), shortDescription.toMap(), longDescription.toMap(), intendedPurpose, code)
            val previous = positionedAfterSectionId?.let { sectionFor(it) }
            positionSection(section, previous)
        }
        is SectionMoved -> with(event) {
            val section = sectionFor(sectionId)
            val previous = positionedAfterSectionId?.let { sectionFor(it) }
            positionSection(section, previous)
        }
        is SectionRemoved -> with(event) {
            val section = sectionFor(sectionId)
            replaceSection(section, section.copy(status = Status.removed))
        }
        is SectionRestored -> with(event) {
            val section = sectionFor(sectionId)
            replaceSection(section, section.copy(status = Status.active))
        }
        is SectionRenamed -> with(event) {
            val section = sectionFor(sectionId)
            replaceSection(section, section.copy(name = section.name + (locale to name)))
        }
        is SectionShortDescriptionChanged -> with(event) {
            val section = sectionFor(sectionId)
            replaceSection(section, section.copy(shortDescription = section.shortDescription + (locale to text)))
        }
        is SectionLongDescriptionChanged -> with(event) {
            val section = sectionFor(sectionId)
            replaceSection(section, section.copy(longDescription = section.longDescription + (locale to text)))
        }
        is SectionDescriptionChanged -> {
            val section = sectionFor(event.sectionId)
            replaceSection(section, section.copy(
                shortDescription = section.shortDescription + (event.locale to event.shortDescription),
                longDescription = section.longDescription + (event.locale to event.longDescription)
            ))
        }
        is SectionDescriptionsRemoved -> {
            val section = sectionFor(event.sectionId)
            replaceSection(section, section.copy(shortDescription = emptyMap(), longDescription = emptyMap()))
        }
        is QuestionPositioned -> {
            val section = sectionFor(event.sectionId)
            positionQuestion(section, event.questionId, event.positionedAfterQuestionId)
        }
        is DemographicSectionsPositionedAtTop -> {
            this.copy(demographicSectionsPlacement = top)
        }
        is DemographicSectionsPositionedAtBottom -> {
            this.copy(demographicSectionsPlacement = bottom)
        }
        is QuestionHiddenFromCapture -> {
            val section = sectionForQuestionId(event.questionId)
            replaceSection(section, section.copy(questions = section.questions - event.questionId))
        }
    }

    fun update(command: SurveyCaptureLayoutUpdateCommand): Either<SurveyCaptureLayoutCommandError, List<SurveyCaptureLayoutUpdateEvent>> = when (command) {
        is AddSection -> when {
            hasSection(command.sectionId) -> SectionAlreadyAdded.left()
            hasSectionCode(command.code) -> SectionCodeNotUnique.left()
            command.positionedAfterSectionId != null && sectionFor(command.positionedAfterSectionId).intendedPurpose != command.intendedPurpose -> SectionHasDifferentIntendedPurpose.left()
            else -> with(command) { listOf(SectionAdded(sectionId, name, shortDescription, longDescription, intendedPurpose, code, positionedAfterSectionId, addedAt)).right() }
        }
        is MoveSection -> if (command.positionedAfterSectionId != null) when {
            !hasSection(command.sectionId) || !hasSection(command.positionedAfterSectionId) -> SectionNotFound.left()
            indexOf(command.sectionId) == indexOf(command.positionedAfterSectionId) + 1 -> SectionAlreadyMoved.left()
            sectionFor(command.sectionId).intendedPurpose != sectionFor(command.positionedAfterSectionId).intendedPurpose -> SectionHasDifferentIntendedPurpose.left()
            else -> with(command) { listOf(SectionMoved(sectionId, positionedAfterSectionId, movedAt)).right() }
        } else when {
            !hasSection(command.sectionId) -> SectionNotFound.left()
            indexOf(command.sectionId) == 0 -> SectionAlreadyMoved.left()
            else -> with(command) { listOf(SectionMoved(sectionId, positionedAfterSectionId, movedAt)).right() }
        }
        is RemoveSection -> when {
            !hasSection(command.sectionId) -> SectionNotFound.left()
            sectionFor(command.sectionId).status == Status.removed -> SectionAlreadyRemoved.left()
            else -> with(command) { listOf(SectionRemoved(sectionId, removedAt)).right() }
        }
        is RestoreSection -> when {
            !hasSection(command.sectionId) -> SectionNotFound.left()
            sectionFor(command.sectionId).status == Status.active -> SectionAlreadyRestored.left()
            else -> with(command) { listOf(SectionRestored(sectionId, restoredAt)).right() }
        }
        is RenameSection -> when {
            !hasSection(command.sectionId) -> SectionNotFound.left()
            sectionFor(command.sectionId).name[command.locale] == command.name -> RenameAlreadyActioned.left()
            else -> with(command) { listOf(SectionRenamed(sectionId, name, locale, renamedAt)).right() }
        }
        is ChangeSectionShortDescription -> when {
            !hasSection(command.sectionId) -> SectionNotFound.left()
            sectionFor(command.sectionId).shortDescription[command.locale] == command.text -> ShortDescriptionAlreadyChanged.left()
            else -> with(command) { listOf(SectionShortDescriptionChanged(sectionId, text, locale, changedAt)).right() }
        }
        is ChangeSectionLongDescription -> when {
            !hasSection(command.sectionId) -> SectionNotFound.left()
            sectionFor(command.sectionId).longDescription[command.locale] == command.text -> LongDescriptionAlreadyChanged.left()
            else -> with(command) { listOf(SectionLongDescriptionChanged(sectionId, text, locale, changedAt)).right() }
        }
        is RemoveSectionDescriptions -> when {
            !hasSection(command.sectionId) -> SectionNotFound.left()
            sectionFor(command.sectionId).let { it.shortDescription.isEmpty() || it.longDescription.isEmpty()} -> SectionDescriptionsAlreadyRemoved.left()
            else -> with(command) { listOf(SectionDescriptionsRemoved(sectionId, removedAt)).right() }
        }
        is PositionQuestion -> if (command.positionedAfterQuestionId != null) when {
            !hasSection(command.sectionId) -> SectionNotFound.left()
            !questionExistsInSection(command.positionedAfterQuestionId) -> QuestionNotFound.left()
            sectionFor(command.sectionId) != sectionForQuestionId(command.positionedAfterQuestionId) -> PositionedAfterQuestionInWrongSection.left()
            sectionFor(command.sectionId).questions.let { it.indexOf(command.questionId) == it.indexOf(command.positionedAfterQuestionId) + 1 } -> QuestionAlreadyInPosition.left()
            else -> with(command) { listOf(QuestionPositioned(questionId, positionedAfterQuestionId, sectionId, positionedAt)).right() }
        } else when {
            !hasSection(command.sectionId) -> SectionNotFound.left()
            sectionFor(command.sectionId).questions.firstOrNull() == command.questionId -> QuestionAlreadyInPosition.left()
            else -> with(command) { listOf(QuestionPositioned(questionId, positionedAfterQuestionId, sectionId, positionedAt)).right() }
        }
        is PositionDemographicSections -> when(command.placement) {
            demographicSectionsPlacement -> DemographicSectionsAlreadyPositioned.left()
            top -> with(command) { listOf(DemographicSectionsPositionedAtTop(positionedAt)).right() }
            bottom -> with(command) { listOf(DemographicSectionsPositionedAtTop(positionedAt)).right() }
        }
        is HideQuestionFromCapture -> when {
            !questions.contains(command.questionId) -> QuestionNotFound.left()
            !questionExistsInSection(command.questionId) -> QuestionAlreadyRemovedFromSection.left()
            else -> with(command) { listOf(QuestionHiddenFromCapture(questionId, hiddenAt)).right() }
        }
    }

    private fun sectionForQuestionId(questionId: UUID) = sectionFor { it.questions.contains(questionId) }!!

    private fun questionExistsInSection(questionId: UUID) = sectionFor { it.questions.contains(questionId) } != null

    private fun sectionFor(sectionId: UUID) = sectionFor { it.sectionId == sectionId }!!

    private fun hasSection(sectionId: UUID) = sectionFor { it.sectionId == sectionId } != null

    private fun hasSectionCode(code: String) = sectionFor { it.code == code } != null

    private fun sectionFor(predicate: (Section) -> Boolean): Section? {
        return sectionsForIntendedPurpose.values.flatten().find(predicate)
    }

    private fun indexOf(sectionId: UUID): Int {
        return sectionsForIntendedPurpose.values.flatten().map { it.sectionId }.indexOf(sectionId)
    }

    private fun replaceSection(section: Section, replacement: Section): SurveyCaptureLayoutAggregate {
        val sections = sectionsForIntendedPurpose.getValue(section.intendedPurpose)
        val updated = sections.replace(section, replacement)
        return this.copy(
            sectionsForIntendedPurpose = sectionsForIntendedPurpose + (section.intendedPurpose to updated)
        )
    }

    private fun positionSection(section: Section, previous: Section?): SurveyCaptureLayoutAggregate {
        val sections = sectionsForIntendedPurpose.getOrDefault(section.intendedPurpose, emptyList())
        val updated = sections.moveAfter(section, previous)
        return this.copy(
            sectionsForIntendedPurpose = sectionsForIntendedPurpose + (section.intendedPurpose to updated)
        )
    }

    private fun positionQuestion(section: Section, questionId: UUID, positionedAfterQuestionId: UUID?): SurveyCaptureLayoutAggregate {
        val sections = sectionsForIntendedPurpose.getOrDefault(section.intendedPurpose, emptyList())
        val updated = sections.replace(
            section,
            section.copy(questions = section.questions.moveAfter(questionId, positionedAfterQuestionId))
        )
        return this.copy(
            sectionsForIntendedPurpose = sectionsForIntendedPurpose + (section.intendedPurpose to updated),
            questions = questions + questionId
        )
    }
}

data class Section(
    val sectionId: UUID,
    val name: Map<Locale, String>,
    val shortDescription: Map<Locale, String>,
    val longDescription: Map<Locale, String>,
    val intendedPurpose: IntendedPurpose,
    val code: String,
    val questions: List<UUID> = emptyList(),
    val status: Status = Status.active
)

enum class Status {
    active, removed
}

sealed class SurveyCaptureLayoutCommand : Command

sealed class SurveyCaptureLayoutCreationCommand : SurveyCaptureLayoutCommand(), CreationCommand
data class Generate(override val aggregateId: UUID, val surveyId: UUID, val generatedAt: DateTime) : SurveyCaptureLayoutCreationCommand()

sealed class SurveyCaptureLayoutUpdateCommand : SurveyCaptureLayoutCommand(), UpdateCommand
data class AddSection(
    override val aggregateId: UUID,
    val sectionId: UUID,
    val name: List<LocalizedText>,
    val shortDescription: List<LocalizedText>,
    val longDescription: List<LocalizedText>,
    val intendedPurpose: IntendedPurpose,
    val code: String,
    val positionedAfterSectionId: UUID?,
    val addedAt: DateTime
) : SurveyCaptureLayoutUpdateCommand() {
    init {
        require(name.isNotEmpty()) { "must contain a name in at least one locale"}
    }
}

data class MoveSection(
    override val aggregateId: UUID,
    val sectionId: UUID,
    val positionedAfterSectionId: UUID?,
    val movedAt: DateTime
) : SurveyCaptureLayoutUpdateCommand()

data class RemoveSection(
    override val aggregateId: UUID,
    val sectionId: UUID,
    val removedAt: DateTime
) : SurveyCaptureLayoutUpdateCommand()

data class RestoreSection(
    override val aggregateId: UUID,
    val sectionId: UUID,
    val restoredAt: DateTime
) : SurveyCaptureLayoutUpdateCommand()

data class RenameSection(
    override val aggregateId: UUID,
    val sectionId: UUID,
    val name: String,
    val locale: Locale,
    val renamedAt: DateTime
) : SurveyCaptureLayoutUpdateCommand() {
    init {
        require(name.length <= MAX_TEXT_SIZE)
    }
}

data class ChangeSectionShortDescription(
    override val aggregateId: UUID,
    val sectionId: UUID,
    val text: String,
    val locale: Locale,
    val changedAt: DateTime
) : SurveyCaptureLayoutUpdateCommand() {
    init {
        require(text.length <= MAX_TEXT_SIZE)
    }
}

data class ChangeSectionLongDescription(
    override val aggregateId: UUID,
    val sectionId: UUID,
    val text: String,
    val locale: Locale,
    val changedAt: DateTime
) : SurveyCaptureLayoutUpdateCommand() {
    init {
        require(text.length <= MAX_TEXT_SIZE)
    }
}

data class RemoveSectionDescriptions(
    override val aggregateId: UUID,
    val sectionId: UUID,
    val removedAt: DateTime
) : SurveyCaptureLayoutUpdateCommand()

data class PositionQuestion(
    override val aggregateId: UUID,
    val questionId: UUID,
    val positionedAfterQuestionId: UUID?,
    val sectionId: UUID,
    val positionedAt: DateTime
) : SurveyCaptureLayoutUpdateCommand()

data class PositionDemographicSections(
    override val aggregateId: UUID,
    val placement: DemographicSectionPosition,
    val positionedAt: DateTime
) : SurveyCaptureLayoutUpdateCommand()

data class HideQuestionFromCapture(
    override val aggregateId: UUID,
    val questionId: UUID,
    val hiddenAt: DateTime
) : SurveyCaptureLayoutUpdateCommand()

sealed class SurveyCaptureLayoutEvent : DomainEvent
sealed class SurveyCaptureLayoutCreationEvent : SurveyCaptureLayoutEvent(), CreationEvent
data class Generated(
    val surveyId: UUID,
    val generatedAt: DateTime
) : SurveyCaptureLayoutCreationEvent()
data class Snapshot(
    val sectionsForIntendedPurpose: Map<IntendedPurpose, List<Section>> = emptyMap(),
    val demographicSectionsPlacement: DemographicSectionPosition = bottom,
    val questions: Set<UUID> = emptySet()
) : SurveyCaptureLayoutCreationEvent()

sealed class SurveyCaptureLayoutUpdateEvent : SurveyCaptureLayoutEvent(), UpdateEvent
data class SectionAdded(
    val sectionId: UUID,
    val name: List<LocalizedText>,
    val shortDescription: List<LocalizedText>,
    val longDescription: List<LocalizedText>,
    val intendedPurpose: IntendedPurpose,
    val code: String,
    val positionedAfterSectionId: UUID?,
    val addedAt: DateTime
) : SurveyCaptureLayoutUpdateEvent()

data class SectionMoved(
    val sectionId: UUID,
    val positionedAfterSectionId: UUID?,
    val movedAt: DateTime
) : SurveyCaptureLayoutUpdateEvent()

data class SectionRemoved(
    val sectionId: UUID,
    val removedAt: DateTime
) : SurveyCaptureLayoutUpdateEvent()

data class SectionRestored(
    val sectionId: UUID,
    val restoredAt: DateTime
) : SurveyCaptureLayoutUpdateEvent()

data class SectionRenamed(
    val sectionId: UUID,
    val name: String,
    val locale: Locale,
    val renamedAt: DateTime
) : SurveyCaptureLayoutUpdateEvent()

data class SectionShortDescriptionChanged(
    val sectionId: UUID,
    val text: String,
    val locale: Locale,
    val changedAt: DateTime
) : SurveyCaptureLayoutUpdateEvent()

data class SectionLongDescriptionChanged(
    val sectionId: UUID,
    val text: String,
    val locale: Locale,
    val changedAt: DateTime
) : SurveyCaptureLayoutUpdateEvent()

data class SectionDescriptionChanged(
    val sectionId: UUID,
    val shortDescription: String,
    val longDescription: String,
    val locale: Locale,
    val changedAt: DateTime
) : SurveyCaptureLayoutUpdateEvent()

data class SectionDescriptionsRemoved(
    val sectionId: UUID,
    val removedAt: DateTime
) : SurveyCaptureLayoutUpdateEvent()

data class QuestionPositioned(
    val questionId: UUID,
    val positionedAfterQuestionId: UUID?,
    val sectionId: UUID,
    val positionedAt: DateTime
) : SurveyCaptureLayoutUpdateEvent()

data class DemographicSectionsPositionedAtTop(
    val positionedAt: DateTime
) : SurveyCaptureLayoutUpdateEvent()

data class DemographicSectionsPositionedAtBottom(
    val positionedAt: DateTime
) : SurveyCaptureLayoutUpdateEvent()

data class QuestionHiddenFromCapture(
    val questionId: UUID,
    val hiddenAt: DateTime
) : SurveyCaptureLayoutUpdateEvent()

sealed class SurveyCaptureLayoutCommandError : DomainError
object DemographicSectionsAlreadyPositioned : SurveyCaptureLayoutCommandError()
object DescriptionsAlreadyChanged : SurveyCaptureLayoutCommandError(), AlreadyActionedCommandError
object ShortDescriptionAlreadyChanged : SurveyCaptureLayoutCommandError(), AlreadyActionedCommandError
object LongDescriptionAlreadyChanged : SurveyCaptureLayoutCommandError(), AlreadyActionedCommandError
object InvalidOrderForSections : SurveyCaptureLayoutCommandError()
object InvalidSectionId : SurveyCaptureLayoutCommandError()
object RenameAlreadyActioned : SurveyCaptureLayoutCommandError(), AlreadyActionedCommandError
object SectionAlreadyAdded : SurveyCaptureLayoutCommandError(), AlreadyActionedCommandError
object SectionAlreadyMoved : SurveyCaptureLayoutCommandError(), AlreadyActionedCommandError
object SectionAlreadyRemoved : SurveyCaptureLayoutCommandError(), AlreadyActionedCommandError
object SectionAlreadyRestored : SurveyCaptureLayoutCommandError(), AlreadyActionedCommandError
object SectionCodeNotUnique : SurveyCaptureLayoutCommandError()
object SectionDescriptionsAlreadyRemoved : SurveyCaptureLayoutCommandError(), AlreadyActionedCommandError
object SectionNotFound : SurveyCaptureLayoutCommandError()
object SectionHasDifferentIntendedPurpose : SurveyCaptureLayoutCommandError()
object QuestionAlreadyInPosition : SurveyCaptureLayoutCommandError(), AlreadyActionedCommandError
object QuestionNotFound : SurveyCaptureLayoutCommandError()
object QuestionAlreadyRemovedFromSection : SurveyCaptureLayoutCommandError(), AlreadyActionedCommandError
object PositionedAfterQuestionInWrongSection : SurveyCaptureLayoutCommandError()

enum class IntendedPurpose {
    standard, demographic
}

enum class DemographicSectionPosition {
    top, bottom
}

data class LocalizedText(val text: String, val locale: Locale) {
    init {
        require(text.length <= MAX_TEXT_SIZE)
    }
}

fun List<LocalizedText>.toMap(): Map<Locale, String> = this.map { it.locale to it.text }.toMap()

fun <T> List<T>.moveAfter(item: T, previous: T?): List<T> {
    val filtered = this.filter { it == item }
    val head = filtered.takeWhile { it != previous }
    val tail = filtered.subList(head.size, filtered.size) // TODO check off-by-one here
    return head + item + tail
}

fun <T> List<T>.replace(old: T, new: T): List<T> {
    return this.map { if (it == old) new else it }
}

const val MAX_TEXT_SIZE = 2000
