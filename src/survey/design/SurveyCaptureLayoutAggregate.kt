package survey.design

import eventsourcing.*
import java.lang.RuntimeException
import java.util.*

data class SurveyCaptureLayoutAggregate(
    override val aggregateId: UUID,
    val sectionsForIntendedPurpose: Map<IntendedPurpose, List<Section>> = emptyMap(),
    val demographicSectionsPlacement: DemographicSectionPosition = DemographicSectionPosition.bottom,
    val questions: List<UUID> = emptyList()
) : Aggregate<SurveyCaptureLayoutUpdateCommand, SurveyCaptureLayoutUpdateEvent, SurveyCaptureLayoutError, SurveyCaptureLayoutAggregate> {

    companion object : AggregateConstructor<Nothing, SurveyCaptureLayoutCreationEvent, SurveyCaptureLayoutError, SurveyCaptureLayoutAggregate> {
        override fun create(event: SurveyCaptureLayoutCreationEvent): SurveyCaptureLayoutAggregate = when(event) {
            is Generated -> SurveyCaptureLayoutAggregate(event.aggregateId)
        }

        override fun handle(command: Nothing): Result<CreationEvent, SurveyCaptureLayoutError> {
            throw RuntimeException("Figure out how this is created")
        }
    }

    override fun update(event: SurveyCaptureLayoutUpdateEvent): SurveyCaptureLayoutAggregate = when(event) {
        is SectionAdded -> with(event) {
            val section = Section(sectionId, name.toMap(), shortDescription.toMap(), longDescription.toMap(), intendedPurpose, code)
            val positionedAfterSection = event.positionedAfterSectionId?.let { sectionFor(it) }
            positionSection(section, positionedAfterSection)
        }
        is SectionMoved -> {
            val section = sectionFor(event.sectionId)
            val positionedAfterSection = event.positionedAfterSectionId?.let { sectionFor(it) }
            positionSection(section, positionedAfterSection)
        }
        is SectionRemoved -> {
            val section = sectionFor(event.sectionId)
            replaceSection(section, section.copy(status = Status.removed))
        }
        is SectionRestored -> {
            val section = sectionFor(event.sectionId)
            replaceSection(section, section.copy(status = Status.active))
        }
        is SectionRenamed -> {
            val section = sectionFor(event.sectionId)
            replaceSection(section, section.copy(name = section.name + (event.locale to event.name)))
        }
        is SectionShortDescriptionChanged -> {
            val section = sectionFor(event.sectionId)
            replaceSection(section, section.copy(shortDescription = section.shortDescription + (event.locale to event.text)))
        }
        is SectionLongDescriptionChanged -> {
            val section = sectionFor(event.sectionId)
            replaceSection(section, section.copy(longDescription = section.longDescription + (event.locale to event.text)))
        }
        is SectionDescriptionChanged -> {
            val section = sectionFor(event.sectionId)
            replaceSection(
                section,
                section.copy(
                shortDescription = section.shortDescription + (event.locale to event.shortDescription),
                longDescription = section.longDescription + (event.locale to event.longDescription)
            ))
        }
        is SectionDescriptionsRemoved -> {
            val section = sectionFor(event.sectionId)
            replaceSection(
                section,
                section.copy(
                shortDescription = emptyMap(),
                longDescription = emptyMap()
            ))
        }
        is QuestionPositioned -> {
            val section = sectionFor(event.sectionId)
            positionQuestion(section, event.questionId, event.positionedAfterQuestionId)

        }
        is DemographicSectionsPositionedAtTop -> {
            this.copy(demographicSectionsPlacement = DemographicSectionPosition.top)
        }
        is DemographicSectionsPositionedAtBottom  -> {
            this.copy(demographicSectionsPlacement = DemographicSectionPosition.bottom)
        }
        is QuestionHiddenFromCaptureCommand -> {
            val section = sectionFor { it.questions.contains(event.questionId) }
            replaceSection(
                section,
                section.copy(
                    questions = questions - event.questionId
                ))
        }
    }

    private fun sectionFor(sectionId: UUID): Section {
        return sectionFor { it.sectionId == sectionId }
    }

    private fun sectionFor(predicate: (Section) -> Boolean): Section {
        return sectionsForIntendedPurpose.values.flatten().first(predicate)
    }

    private fun replaceSection(section: Section, replacement: Section): SurveyCaptureLayoutAggregate {
        val sections = sectionsForIntendedPurpose.getValue(section.intendedPurpose)
        val updated = sections.replace(section, replacement)
        return this.copy(
            sectionsForIntendedPurpose = sectionsForIntendedPurpose + (section.intendedPurpose to updated)
        )
    }

    private fun positionSection(section: Section, positionedAfterSection: Section?): SurveyCaptureLayoutAggregate {
        val sections = sectionsForIntendedPurpose.getOrDefault(section.intendedPurpose, emptyList())
        val updated = sections.moveAfter(section, positionedAfterSection)
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

    override fun handle(command: SurveyCaptureLayoutUpdateCommand): Result<SurveyCaptureLayoutUpdateEvent, SurveyCaptureLayoutError> = when(command) {
        is AddSection -> TODO()
        is MoveSection -> TODO()
        is RemoveSection -> TODO()
        is RestoreSection -> TODO()
        is RenameSection -> TODO()
        is ChangeSectionShortDescription -> TODO()
        is ChangeSectionLongDescription -> TODO()
        is RemoveSectionDescriptions -> TODO()
        is PositionQuestion -> TODO()
        is PositionDemographicSections -> TODO()
        is HideQuestionFromCapture -> TODO()
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

sealed class SurveyCaptureLayoutCommand
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

sealed class SurveyCaptureLayoutEvent
sealed class SurveyCaptureLayoutCreationEvent : SurveyCaptureLayoutEvent(), CreationEvent
data class Generated(
    override val aggregateId: UUID,
    val surveyId: UUID,
    val generatedAt: Date
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
data class QuestionHiddenFromCaptureCommand(
    val questionId: UUID,
    val hiddenAt: Date
) : SurveyCaptureLayoutUpdateEvent()

sealed class SurveyCaptureLayoutError : Error
sealed class AlreadyActionedError : SurveyCaptureLayoutError()
object DemographicSectionsAlreadyPositioned : SurveyCaptureLayoutError()
object DescriptionsAlreadyChangedException :  AlreadyActionedError()
object ShortDescriptionAlreadyChangedException :  AlreadyActionedError()
object LongDescriptionAlreadyChangedException :  AlreadyActionedError()
object InvalidOrderForSectionsException :  SurveyCaptureLayoutError()
object InvalidSectionIdException :  SurveyCaptureLayoutError()
object RenameAlreadyActionedException :  AlreadyActionedError()
object SectionAlreadyAddedException :  AlreadyActionedError()
object SectionAlreadyMoved :  AlreadyActionedError()
object SectionAlreadyRemovedException :  AlreadyActionedError()
object SectionAlreadyRestoredException :  AlreadyActionedError()
object SectionCodeNotUniqueException :  SurveyCaptureLayoutError()
object SectionDescriptionsAlreadyRemoved :  AlreadyActionedError()
object SectionNotFoundException :  SurveyCaptureLayoutError()
object SectionHasDifferentIntendedPurpose :  SurveyCaptureLayoutError()
object QuestionAlreadyInPosition :  AlreadyActionedError()
object QuestionNotFoundException :  SurveyCaptureLayoutError()
object QuestionAlreadyRemovedFromSectionException :  AlreadyActionedError()
object PositionedAfterQuestionInWrongSection :  SurveyCaptureLayoutError()

enum class IntendedPurpose {
    standard, demographic
}

enum class DemographicSectionPosition {
    top, bottom
}
data class LocalizedText(val text: String, val locale: Locale) {
    init { require(text.length <= MAX_TEXT_SIZE) }
}

fun List<LocalizedText>.toMap(): Map<Locale, String> = this.map { it.locale to it.text }.toMap()

fun <T> List<T>.moveAfter(item: T, after: T?): List<T> {
    val removed = this - item
    val index = if (after != null) removed.indexOf(after) + 1 else 0
    return removed.insertAt(item, index)
}

fun <T> List<T>.replace(item: T, replacement: T): List<T> {
    val index = this.indexOf(item)
    val removed = this - item
    return removed.insertAt(replacement, index)
}

fun <T> List<T>.insertAt(item: T, index: Int): List<T> {
    return this.subList(0, index) + item + this.subList(index + 1, this.size)
}

const val MAX_TEXT_SIZE = 2000

