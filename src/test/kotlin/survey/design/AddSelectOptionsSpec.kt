package survey.design

import io.kotlintest.shouldBe
import io.kotlintest.shouldThrow
import io.kotlintest.specs.StringSpec
import java.util.*

sealed class QuestionCommand
data class AddSelectOptions(
	val selectOptions: List<SelectOption>,
	val positionedAfterSelectOptionId: UUID?,
	val addedAt: Date
) : QuestionCommand()

data class SelectOption(val selectOptionId: UUID, val value: List<LocalizedText>) {
	init {
		require(value.isNotEmpty()) { "Must provide at least one translation"}
	}
}
data class LocalizedText(val text: String, val locale: Locale)

enum class Locale {
	en, de
}

class AddSelectOptionsSpec : StringSpec({
	"should fail to create when no translations provided for a Select Option" {
		val exception = shouldThrow<IllegalArgumentException> {
			AddSelectOptions(listOf(SelectOption(UUID.randomUUID(), emptyList())), UUID.randomUUID(), Date())
		}
		exception.message shouldBe "Must provide at least one translation"
	}

	"should successfully create when at least one translation provided for a SelectOption" {
		AddSelectOptions(listOf(SelectOption(UUID.randomUUID(), listOf(LocalizedText("Cat", Locale.en)))), UUID.randomUUID(), Date())
	}
})

