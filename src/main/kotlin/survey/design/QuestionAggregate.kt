package survey.design

import java.util.UUID
import java.util.Date

sealed class QuestionCommand
data class AddSelectOptions(
    val selectOptions: List<SelectOption>,
    val positionedAfterSelectOptionId: UUID?,
    val addedAt: Date
) : QuestionCommand()

data class SelectOption(val selectOptionId: UUID, val value: Map<Locale, String>) {
    init { require(value.isNotEmpty()) }
}
