package survey.design

import eventsourcing.Left
import eventsourcing.ReadOnlyDatabase
import eventsourcing.Right
import io.kotlintest.shouldBe
import io.kotlintest.specs.ShouldSpec
import java.lang.IllegalStateException
import java.util.*

class SurveyAggregateSpec : ShouldSpec({
	val namedAt = Date()
	val createdAt = Date()
	val deletedAt = Date()
	val restoredAt = Date()
	val name = mapOf(Locale.en to "name")
	val accountId = UUID.randomUUID()
	val aggregateId = UUID.randomUUID()
	val surveyCaptureLayoutAggregateId = UUID.randomUUID()

	"Created" {
		should("create a new Survey") {
			SurveyAggregate.create(Created(aggregateId, name, accountId, createdAt)) shouldBe
				SurveyAggregate(aggregateId, name, accountId, deleted = false)
		}
	}

	"Snapshot" {
		should("create a Survey at a point in time") {
			SurveyAggregate.create(Snapshot(aggregateId, name, accountId, true, createdAt)) shouldBe
				SurveyAggregate(aggregateId, name, accountId, true)
		}
	}

	"Renamed" {
		should("update the name for one Locale") {
			SurveyAggregate
				.create(Created(aggregateId, name, accountId, createdAt))
				.update(Renamed(aggregateId, "rename", Locale.en, Date()))
				.name.getValue(Locale.en) shouldBe "rename"
		}
	}

	"Deleted" {
		should("set the deleted flag to true") {
			SurveyAggregate
				.create(Created(aggregateId, name, accountId, createdAt))
				.update(Deleted(aggregateId, Date()))
				.deleted shouldBe true
		}
	}

	"Restored" {
		should("set the deleted flag to true") {
			SurveyAggregate
				.create(Created(aggregateId, name, accountId, createdAt))
				.update(Deleted(aggregateId, Date()))
				.update(Restored(aggregateId, Date()))
				.deleted shouldBe false
		}
	}

	"Create" {
		should("return Created event") {
			SurveyAggregate.create(
				Create(aggregateId, surveyCaptureLayoutAggregateId, name, accountId, createdAt), NameTaken(false)
			) shouldBe
				Right.list(Created(aggregateId, name, accountId, createdAt))
		}

		"when Survey name already taken" {
			should("fail with SurveyNameNotUnique") {
				SurveyAggregate.create(
					Create(aggregateId, surveyCaptureLayoutAggregateId, name, accountId, createdAt), NameTaken(true)
				) shouldBe
					Left(SurveyNameNotUnique)
			}
		}
	}

	"Rename" {
		should("return Renamed event") {
			SurveyAggregate
				.create(Created(aggregateId, name, accountId, createdAt))
				.update(Rename(aggregateId, "rename", Locale.en, namedAt), NameTaken(false)) shouldBe
				Right.list(Renamed(aggregateId, "rename", Locale.en, namedAt))
		}

		"when the name is the same" {
			should("fail with SurveyNameNotUnique") {
				SurveyAggregate
					.create(Created(aggregateId, name, accountId, createdAt))
					.update(Rename(aggregateId, "rename", Locale.en, namedAt), NameTaken(true)) shouldBe
					Left(SurveyNameNotUnique)
			}
		}

		"when the name is the same for a different locale" {
			should("return Renamed event") {
				SurveyAggregate
					.create(Created(aggregateId, name, accountId, createdAt))
					.update(Rename(aggregateId, name.getValue(Locale.en), Locale.de, namedAt), NameTaken(false)) shouldBe
					Right.list(Renamed(aggregateId, name.getValue(Locale.en), Locale.de, namedAt))
			}
		}
	}

	"Delete" {
		should("return Deleted event") {
			SurveyAggregate
				.create(Created(aggregateId, name, accountId, createdAt))
				.update(Delete(aggregateId, deletedAt), Stub()) shouldBe
				Right.list(Deleted(aggregateId, deletedAt))
		}

		"when Survey already deleted" {
			should("fail with AlreadyDeleted") {
				SurveyAggregate
					.create(Created(aggregateId, name, accountId, createdAt))
					.update(Deleted(aggregateId, deletedAt))
					.update(Delete(aggregateId, deletedAt), Stub()) shouldBe
					Left(AlreadyDeleted)
			}
		}
	}

	"Restore" {
		should("fail with NotDeleted") {
			SurveyAggregate
				.create(Created(aggregateId, name, accountId, createdAt))
				.update(Restore(aggregateId, restoredAt), Stub()) shouldBe
				Left(NotDeleted)
		}

		"when Survey already deleted" {
			should("return Restored event") {
				SurveyAggregate
					.create(Created(aggregateId, name, accountId, createdAt))
					.update(Deleted(aggregateId, deletedAt))
					.update(Restore(aggregateId, restoredAt), Stub()) shouldBe
					Right.list(Restored(aggregateId, restoredAt))
			}
		}
	}
})

class Stub : SurveyNamesProjection(StubReadOnlyDatabase()) {
	override fun nameExistsFor(accountId: UUID, name: String, locale: Locale): Boolean {
		throw IllegalStateException("Should not have been called")
	}
}

class NameTaken(val taken: Boolean) : SurveyNamesProjection(StubReadOnlyDatabase()) {
	override fun nameExistsFor(accountId: UUID, name: String, locale: Locale) = taken
}

class StubReadOnlyDatabase : ReadOnlyDatabase
