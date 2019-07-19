package survey.design

import eventsourcing.Aggregate
import eventsourcing.AggregateConstructor
import eventsourcing.Command
import eventsourcing.CreationEvent
import eventsourcing.Either
import eventsourcing.Right
import eventsourcing.UpdateCommand
import eventsourcing.UpdateEvent
import eventsourcing.CommandError
import eventsourcing.Event
import eventsourcing.Left
import java.util.Date
import java.util.UUID


data class SurveyCaptureLayoutAggregate(
    override val aggregateId: UUID,
    val sectionsForIntendedPurpose: Map<IntendedPurpose, List<Section>> = emptyMap(),
    val demographicSectionsPlacement: DemographicSectionPosition = DemographicSectionPosition.bottom,
    val questions: Set<UUID> = emptySet()
) : Aggregate<SurveyCaptureLayoutUpdateCommand, SurveyCaptureLayoutUpdateEvent, SurveyCaptureLayoutCommandError, SurveyCaptureLayoutAggregate> {

    companion object : AggregateConstructor<SurveyCreationCommand, SurveyCaptureLayoutCreationEvent, SurveyCaptureLayoutCommandError, SurveyCaptureLayoutAggregate> {
        override fun create(event: SurveyCaptureLayoutCreationEvent): SurveyCaptureLayoutAggregate = when (event) {
            is Generated -> SurveyCaptureLayoutAggregate(event.aggregateId)
        }

        override fun create(command: SurveyCreationCommand): Either<SurveyCaptureLayoutCommandError, List<SurveyCaptureLayoutCreationEvent>> = when (command) {
            is Create -> with(command) { Right.list(Generated(surveyCaptureLayoutAggregateId, aggregateId, createdAt)) }
        }
    }

    override fun update(event: SurveyCaptureLayoutUpdateEvent): SurveyCaptureLayoutAggregate = when (event) {
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
            replaceSection(
                section,
                section.copy(
                    shortDescription = section.shortDescription + (event.locale to event.shortDescription),
                    longDescription = section.longDescription + (event.locale to event.longDescription)
                )
            )
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
            this.copy(demographicSectionsPlacement = DemographicSectionPosition.top)
        }
        is DemographicSectionsPositionedAtBottom -> {
            this.copy(demographicSectionsPlacement = DemographicSectionPosition.bottom)
        }
        is QuestionHiddenFromCaptureCommand -> {
            val section = sectionFor { it.questions.contains(event.questionId) }!!
            replaceSection(
                section,
                section.copy(
                    questions = section.questions - event.questionId
                )
            )
        }
    }

    override fun update(command: SurveyCaptureLayoutUpdateCommand): Either<SurveyCaptureLayoutCommandError, List<SurveyCaptureLayoutUpdateEvent>> = when (command) {
        is AddSection -> with(command) {
            when {
                hasSection(sectionId) -> Left(SectionAlreadyAdded)
                hasSectionCode(code) -> Left(SectionCodeNotUnique)
                hasDifferentIntendedPurpose(positionedAfterSectionId, intendedPurpose) -> Left(SectionHasDifferentIntendedPurpose)
                else -> Right.list(SectionAdded(aggregateId, sectionId, name, shortDescription, longDescription, intendedPurpose, code, positionedAfterSectionId, addedAt))
            }
        }
        is MoveSection -> with(command) {
            if (positionedAfterSectionId != null) when {
                !hasSection(sectionId) || !hasSection(positionedAfterSectionId) -> Left(SectionNotFound)
                indexOf(sectionId) == indexOf(positionedAfterSectionId) + 1 -> Left(SectionAlreadyMoved)
                sectionFor(sectionId).intendedPurpose != sectionFor(positionedAfterSectionId).intendedPurpose -> Left(SectionHasDifferentIntendedPurpose)
                else -> Right.list(SectionMoved(aggregateId, sectionId, positionedAfterSectionId, movedAt))
            } else when {
                !hasSection(sectionId) -> Left(SectionNotFound)
                indexOf(sectionId) == 0 -> Left(SectionAlreadyMoved)
                else -> Right.list(SectionMoved(aggregateId, sectionId, positionedAfterSectionId, movedAt))
            }
        }
        is RemoveSection -> when {
            !hasSection(command.sectionId) -> Left(SectionNotFound)
            sectionFor(command.sectionId).status == Status.removed -> Left(SectionAlreadyRemoved)
            else -> with(command) { Right.list(SectionRemoved(aggregateId, sectionId, removedAt)) }
        }
        is RestoreSection -> when {
            !hasSection(command.sectionId) -> Left(SectionNotFound)
            sectionFor(command.sectionId).status == Status.active -> Left(SectionAlreadyRestored)
            else -> with(command) { Right.list(SectionRestored(aggregateId, sectionId, restoredAt)) }
        }
        is RenameSection -> TODO()
        is ChangeSectionShortDescription -> TODO()
        is ChangeSectionLongDescription -> TODO()
        is RemoveSectionDescriptions -> TODO()
        is PositionQuestion -> TODO()
        is PositionDemographicSections -> TODO()
        is HideQuestionFromCapture -> TODO()
    }

    private fun sectionFor(sectionId: UUID) = sectionFor { it.sectionId == sectionId }!!

    private fun hasSection(sectionId: UUID) = sectionFor { it.sectionId == sectionId } != null

    private fun hasSectionCode(code: String) = sectionFor { it.code == code } != null

    private fun hasDifferentIntendedPurpose(sectionId: UUID?, intendedPurpose: IntendedPurpose): Boolean {
        return sectionId?.let { sectionFor(it).intendedPurpose } == intendedPurpose
    }

    private fun indexOf(sectionId: UUID): Int {
        return sectionsForIntendedPurpose.values.flatten().map { it.sectionId }.indexOf(sectionId)
    }

    private fun sectionFor(predicate: (Section) -> Boolean): Section? {
        return sectionsForIntendedPurpose.values.flatten().find(predicate)
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
    val addedAt: Date
) : SurveyCaptureLayoutUpdateCommand() {
    init {
        require(name.isNotEmpty())
    }
}

data class MoveSection(
    override val aggregateId: UUID,
    val sectionId: UUID,
    val positionedAfterSectionId: UUID?,
    val movedAt: Date
) : SurveyCaptureLayoutUpdateCommand()

data class RemoveSection(
    override val aggregateId: UUID,
    val sectionId: UUID,
    val removedAt: Date
) : SurveyCaptureLayoutUpdateCommand()

data class RestoreSection(
    override val aggregateId: UUID,
    val sectionId: UUID,
    val restoredAt: Date
) : SurveyCaptureLayoutUpdateCommand()

data class RenameSection(
    override val aggregateId: UUID,
    val sectionId: UUID,
    val name: String,
    val locale: Locale,
    val renamedAt: Date
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
    val changedAt: Date
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
    val changedAt: Date
) : SurveyCaptureLayoutUpdateCommand() {
    init {
        require(text.length <= MAX_TEXT_SIZE)
    }
}

data class RemoveSectionDescriptions(
    override val aggregateId: UUID,
    val sectionId: UUID,
    val removedAt: Date
) : SurveyCaptureLayoutUpdateCommand()

data class PositionQuestion(
    override val aggregateId: UUID,
    val questionId: UUID,
    val positionedAfterQuestionId: UUID?,
    val sectionId: UUID,
    val positionedAt: Date
) : SurveyCaptureLayoutUpdateCommand()

data class PositionDemographicSections(
    override val aggregateId: UUID,
    val placement: UUID,
    val positionedAt: Date
) : SurveyCaptureLayoutUpdateCommand()

data class HideQuestionFromCapture(
    override val aggregateId: UUID,
    val questionId: UUID,
    val hiddenAt: Date
) : SurveyCaptureLayoutUpdateCommand()

sealed class SurveyCaptureLayoutEvent : Event
sealed class SurveyCaptureLayoutCreationEvent : SurveyCaptureLayoutEvent(), CreationEvent
data class Generated(
    override val aggregateId: UUID,
    val surveyId: UUID,
    val generatedAt: Date
) : SurveyCaptureLayoutCreationEvent()

sealed class SurveyCaptureLayoutUpdateEvent : SurveyCaptureLayoutEvent(), UpdateEvent
data class SectionAdded(
    override val aggregateId: UUID,
    val sectionId: UUID,
    val name: List<LocalizedText>,
    val shortDescription: List<LocalizedText>,
    val longDescription: List<LocalizedText>,
    val intendedPurpose: IntendedPurpose,
    val code: String,
    val positionedAfterSectionId: UUID?,
    val addedAt: Date
) : SurveyCaptureLayoutUpdateEvent()

data class SectionMoved(
    override val aggregateId: UUID,
    val sectionId: UUID,
    val positionedAfterSectionId: UUID?,
    val movedAt: Date
) : SurveyCaptureLayoutUpdateEvent()

data class SectionRemoved(
    override val aggregateId: UUID,
    val sectionId: UUID,
    val removedAt: Date
) : SurveyCaptureLayoutUpdateEvent()

data class SectionRestored(
    override val aggregateId: UUID,
    val sectionId: UUID,
    val restoredAt: Date
) : SurveyCaptureLayoutUpdateEvent()

data class SectionRenamed(
    override val aggregateId: UUID,
    val sectionId: UUID,
    val name: String,
    val locale: Locale,
    val renamedAt: Date
) : SurveyCaptureLayoutUpdateEvent()

data class SectionShortDescriptionChanged(
    override val aggregateId: UUID,
    val sectionId: UUID,
    val text: String,
    val locale: Locale,
    val changedAt: Date
) : SurveyCaptureLayoutUpdateEvent()

data class SectionLongDescriptionChanged(
    override val aggregateId: UUID,
    val sectionId: UUID,
    val text: String,
    val locale: Locale,
    val changedAt: Date
) : SurveyCaptureLayoutUpdateEvent()

data class SectionDescriptionChanged(
    override val aggregateId: UUID,
    val sectionId: UUID,
    val shortDescription: String,
    val longDescription: String,
    val locale: Locale,
    val changedAt: Date
) : SurveyCaptureLayoutUpdateEvent()

data class SectionDescriptionsRemoved(
    override val aggregateId: UUID,
    val sectionId: UUID,
    val removedAt: Date
) : SurveyCaptureLayoutUpdateEvent()

data class QuestionPositioned(
    override val aggregateId: UUID,
    val questionId: UUID,
    val positionedAfterQuestionId: UUID?,
    val sectionId: UUID,
    val positionedAt: Date
) : SurveyCaptureLayoutUpdateEvent()

data class DemographicSectionsPositionedAtTop(
    override val aggregateId: UUID,
    val positionedAt: Date
) : SurveyCaptureLayoutUpdateEvent()

data class DemographicSectionsPositionedAtBottom(
    override val aggregateId: UUID,
    val positionedAt: Date
) : SurveyCaptureLayoutUpdateEvent()

data class QuestionHiddenFromCaptureCommand(
    override val aggregateId: UUID,
    val questionId: UUID,
    val hiddenAt: Date
) : SurveyCaptureLayoutUpdateEvent()

sealed class SurveyCaptureLayoutCommandError : CommandError
sealed class AlreadyActionedCommandError : SurveyCaptureLayoutCommandError()
object DemographicSectionsAlreadyPositioned : SurveyCaptureLayoutCommandError()
object DescriptionsAlreadyChanged : AlreadyActionedCommandError()
object ShortDescriptionAlreadyChanged : AlreadyActionedCommandError()
object LongDescriptionAlreadyChanged : AlreadyActionedCommandError()
object InvalidOrderForSections : SurveyCaptureLayoutCommandError()
object InvalidSectionId : SurveyCaptureLayoutCommandError()
object RenameAlreadyActioned : AlreadyActionedCommandError()
object SectionAlreadyAdded : AlreadyActionedCommandError()
object SectionAlreadyMoved : AlreadyActionedCommandError()
object SectionAlreadyRemoved : AlreadyActionedCommandError()
object SectionAlreadyRestored : AlreadyActionedCommandError()
object SectionCodeNotUnique : SurveyCaptureLayoutCommandError()
object SectionDescriptionsAlreadyRemoved : AlreadyActionedCommandError()
object SectionNotFound : SurveyCaptureLayoutCommandError()
object SectionHasDifferentIntendedPurpose : SurveyCaptureLayoutCommandError()
object QuestionAlreadyInPosition : AlreadyActionedCommandError()
object QuestionNotFound : SurveyCaptureLayoutCommandError()
object QuestionAlreadyRemovedFromSection : AlreadyActionedCommandError()
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
    val removed = this - item
    val index = if (previous != null) removed.indexOf(previous) + 1 else 0
    return removed.subList(0, index) + item + removed.subList(index + 1, removed.size)
}

fun <T> List<T>.replace(old: T, new: T): List<T> {
    return this.map { if (it == old) new else it }
}

const val MAX_TEXT_SIZE = 2000

