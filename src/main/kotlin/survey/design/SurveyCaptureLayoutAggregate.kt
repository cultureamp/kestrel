package survey.design

import eventsourcing.*
import survey.design.DemographicSectionPosition.bottom
import survey.design.DemographicSectionPosition.top
import java.util.Date
import java.util.UUID


data class SurveyCaptureLayoutAggregate(
    val sectionsForIntendedPurpose: Map<IntendedPurpose, List<Section>> = emptyMap(),
    val demographicSectionsPlacement: DemographicSectionPosition = bottom,
    val questions: Set<UUID> = emptySet()
) : Aggregate {
    companion object {
        fun created(event: SurveyCaptureLayoutCreationEvent) = when (event) {
            is Generated -> SurveyCaptureLayoutAggregate()
            is Snapshot -> with(event) { SurveyCaptureLayoutAggregate(sectionsForIntendedPurpose, demographicSectionsPlacement, questions) }
        }

        fun create(command: SurveyCaptureLayoutCreationCommand): Either<CommandError, Generated> = when (command) {
            is Generate -> with(command) { Right(Generated(surveyId, generatedAt)) }
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

    fun update(command: SurveyCaptureLayoutUpdateCommand): Either<CommandError, List<SurveyCaptureLayoutUpdateEvent>> = when (command) {
        is AddSection -> when {
            hasSection(command.sectionId) -> Left(SectionAlreadyAdded)
            hasSectionCode(command.code) -> Left(SectionCodeNotUnique)
            command.positionedAfterSectionId != null && sectionFor(command.positionedAfterSectionId).intendedPurpose != command.intendedPurpose -> Left(SectionHasDifferentIntendedPurpose)
            else -> with(command) { Right.list(SectionAdded(sectionId, name, shortDescription, longDescription, intendedPurpose, code, positionedAfterSectionId, addedAt)) }
        }
        is MoveSection -> if (command.positionedAfterSectionId != null) when {
            !hasSection(command.sectionId) || !hasSection(command.positionedAfterSectionId) -> Left(SectionNotFound)
            indexOf(command.sectionId) == indexOf(command.positionedAfterSectionId) + 1 -> Left(SectionAlreadyMoved)
            sectionFor(command.sectionId).intendedPurpose != sectionFor(command.positionedAfterSectionId).intendedPurpose -> Left(SectionHasDifferentIntendedPurpose)
            else -> with(command) { Right.list(SectionMoved(sectionId, positionedAfterSectionId, movedAt)) }
        } else when {
            !hasSection(command.sectionId) -> Left(SectionNotFound)
            indexOf(command.sectionId) == 0 -> Left(SectionAlreadyMoved)
            else -> with(command) { Right.list(SectionMoved(sectionId, positionedAfterSectionId, movedAt)) }
        }
        is RemoveSection -> when {
            !hasSection(command.sectionId) -> Left(SectionNotFound)
            sectionFor(command.sectionId).status == Status.removed -> Left(SectionAlreadyRemoved)
            else -> with(command) { Right.list(SectionRemoved(sectionId, removedAt)) }
        }
        is RestoreSection -> when {
            !hasSection(command.sectionId) -> Left(SectionNotFound)
            sectionFor(command.sectionId).status == Status.active -> Left(SectionAlreadyRestored)
            else -> with(command) { Right.list(SectionRestored(sectionId, restoredAt)) }
        }
        is RenameSection -> when {
            !hasSection(command.sectionId) -> Left(SectionNotFound)
            sectionFor(command.sectionId).name[command.locale] == command.name -> Left(RenameAlreadyActioned)
            else -> with(command) { Right.list(SectionRenamed(sectionId, name, locale, renamedAt)) }
        }
        is ChangeSectionShortDescription -> when {
            !hasSection(command.sectionId) -> Left(SectionNotFound)
            sectionFor(command.sectionId).shortDescription[command.locale] == command.text -> Left(ShortDescriptionAlreadyChanged)
            else -> with(command) { Right.list(SectionShortDescriptionChanged(sectionId, text, locale, changedAt)) }
        }
        is ChangeSectionLongDescription -> when {
            !hasSection(command.sectionId) -> Left(SectionNotFound)
            sectionFor(command.sectionId).longDescription[command.locale] == command.text -> Left(LongDescriptionAlreadyChanged)
            else -> with(command) { Right.list(SectionLongDescriptionChanged(sectionId, text, locale, changedAt)) }
        }
        is RemoveSectionDescriptions -> when {
            !hasSection(command.sectionId) -> Left(SectionNotFound)
            sectionFor(command.sectionId).let { it.shortDescription.isEmpty() || it.longDescription.isEmpty()} -> Left(SectionDescriptionsAlreadyRemoved)
            else -> with(command) { Right.list(SectionDescriptionsRemoved(sectionId, removedAt)) }
        }
        is PositionQuestion -> if (command.positionedAfterQuestionId != null) when {
            !hasSection(command.sectionId) -> Left(SectionNotFound)
            !questionExistsInSection(command.positionedAfterQuestionId) -> Left(QuestionNotFound)
            sectionFor(command.sectionId) != sectionForQuestionId(command.positionedAfterQuestionId) -> Left(PositionedAfterQuestionInWrongSection)
            sectionFor(command.sectionId).questions.let { it.indexOf(command.questionId) == it.indexOf(command.positionedAfterQuestionId) + 1 } -> Left(QuestionAlreadyInPosition)
            else -> with(command) { Right.list(QuestionPositioned(questionId, positionedAfterQuestionId, sectionId, positionedAt)) }
        } else when {
            !hasSection(command.sectionId) -> Left(SectionNotFound)
            sectionFor(command.sectionId).questions.first() != command.questionId -> Left(QuestionAlreadyInPosition)
            else -> with(command) { Right.list(QuestionPositioned(questionId, positionedAfterQuestionId, sectionId, positionedAt)) }
        }
        is PositionDemographicSections -> when(command.placement) {
            demographicSectionsPlacement -> Left(DemographicSectionsAlreadyPositioned)
            top -> with(command) { Right.list(DemographicSectionsPositionedAtTop(positionedAt)) }
            bottom -> with(command) { Right.list(DemographicSectionsPositionedAtTop(positionedAt)) }
        }
        is HideQuestionFromCapture -> when {
            !questions.contains(command.questionId) -> Left(QuestionNotFound)
            !questionExistsInSection(command.questionId) -> Left(QuestionAlreadyRemovedFromSection)
            else -> with(command) { Right.list(QuestionHiddenFromCapture(questionId, hiddenAt)) }
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
data class Generate(override val aggregateId: UUID, val surveyId: UUID, val generatedAt: Date) : SurveyCaptureLayoutCreationCommand()

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
    val placement: DemographicSectionPosition,
    val positionedAt: Date
) : SurveyCaptureLayoutUpdateCommand()

data class HideQuestionFromCapture(
    override val aggregateId: UUID,
    val questionId: UUID,
    val hiddenAt: Date
) : SurveyCaptureLayoutUpdateCommand()

sealed class SurveyCaptureLayoutEvent : DomainEvent
sealed class SurveyCaptureLayoutCreationEvent : SurveyCaptureLayoutEvent(), CreationEvent
data class Generated(
    val surveyId: UUID,
    val generatedAt: Date
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
    val addedAt: Date
) : SurveyCaptureLayoutUpdateEvent()

data class SectionMoved(
    val sectionId: UUID,
    val positionedAfterSectionId: UUID?,
    val movedAt: Date
) : SurveyCaptureLayoutUpdateEvent()

data class SectionRemoved(
    val sectionId: UUID,
    val removedAt: Date
) : SurveyCaptureLayoutUpdateEvent()

data class SectionRestored(
    val sectionId: UUID,
    val restoredAt: Date
) : SurveyCaptureLayoutUpdateEvent()

data class SectionRenamed(
    val sectionId: UUID,
    val name: String,
    val locale: Locale,
    val renamedAt: Date
) : SurveyCaptureLayoutUpdateEvent()

data class SectionShortDescriptionChanged(
    val sectionId: UUID,
    val text: String,
    val locale: Locale,
    val changedAt: Date
) : SurveyCaptureLayoutUpdateEvent()

data class SectionLongDescriptionChanged(
    val sectionId: UUID,
    val text: String,
    val locale: Locale,
    val changedAt: Date
) : SurveyCaptureLayoutUpdateEvent()

data class SectionDescriptionChanged(
    val sectionId: UUID,
    val shortDescription: String,
    val longDescription: String,
    val locale: Locale,
    val changedAt: Date
) : SurveyCaptureLayoutUpdateEvent()

data class SectionDescriptionsRemoved(
    val sectionId: UUID,
    val removedAt: Date
) : SurveyCaptureLayoutUpdateEvent()

data class QuestionPositioned(
    val questionId: UUID,
    val positionedAfterQuestionId: UUID?,
    val sectionId: UUID,
    val positionedAt: Date
) : SurveyCaptureLayoutUpdateEvent()

data class DemographicSectionsPositionedAtTop(
    val positionedAt: Date
) : SurveyCaptureLayoutUpdateEvent()

data class DemographicSectionsPositionedAtBottom(
    val positionedAt: Date
) : SurveyCaptureLayoutUpdateEvent()

data class QuestionHiddenFromCapture(
    val questionId: UUID,
    val hiddenAt: Date
) : SurveyCaptureLayoutUpdateEvent()

sealed class SurveyCaptureLayoutCommandError : CommandError
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

