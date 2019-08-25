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
		should("created a new Survey") {
			SurveyAggregate.rehydrated(
				Created(aggregateId, name, accountId, createdAt)
			) shouldBe
				SurveyAggregate(aggregateId, name, accountId, deleted = false)
		}
	}

	"Snapshot" {
		should("created a Survey at a point in time") {
			SurveyAggregate.rehydrated(
				Snapshot(aggregateId, name, accountId, true, createdAt)
			) shouldBe
				SurveyAggregate(aggregateId, name, accountId, true)
		}
	}

	"Renamed" {
		should("updated the name for one Locale") {
			SurveyAggregate.rehydrated(
				Created(aggregateId, name, accountId, createdAt),
				Renamed(aggregateId, "rename", Locale.en, Date())
			).name.getValue(Locale.en) shouldBe
				"rename"
		}
	}

	"Deleted" {
		should("set the deleted flag to true") {
			SurveyAggregate.rehydrated(
				Created(aggregateId, name, accountId, createdAt),
				Deleted(aggregateId, Date())
			).deleted shouldBe
				true
		}
	}

	"Restored" {
		should("set the deleted flag to true") {
			SurveyAggregate.rehydrated(
				Created(aggregateId, name, accountId, createdAt),
				Deleted(aggregateId, Date()),
				Restored(aggregateId, Date())
			).deleted shouldBe
				false
		}
	}

	"CreateSurvey" {
		should("return Created event") {
			SurveyAggregate.create(
				CreateSurvey(aggregateId, surveyCaptureLayoutAggregateId, name, accountId, createdAt), NameTaken(false)
			) shouldBe
				Right(Created(aggregateId, name, accountId, createdAt))
		}

		"when Survey name already taken" {
			should("fail with SurveyNameNotUnique") {
				SurveyAggregate.create(
					CreateSurvey(aggregateId, surveyCaptureLayoutAggregateId, name, accountId, createdAt), NameTaken(true)
				) shouldBe
					Left(SurveyNameNotUnique)
			}
		}
	}

	"Rename" {
		should("return Renamed event") {
			SurveyAggregate.rehydrated(
				Created(aggregateId, name, accountId, createdAt)
			).update(
				Rename(aggregateId, "rename", Locale.en, namedAt), NameTaken(false)
			) shouldBe
				Right.list(Renamed(aggregateId, "rename", Locale.en, namedAt))
		}

		"when the name is the same" {
			should("fail with SurveyNameNotUnique") {
				SurveyAggregate.rehydrated(
					Created(aggregateId, name, accountId, createdAt)
				).update(
					Rename(aggregateId, "rename", Locale.en, namedAt), NameTaken(true)
				) shouldBe
					Left(SurveyNameNotUnique)
			}
		}

		"when the name is the same for a different locale" {
			should("return Renamed event") {
				SurveyAggregate.rehydrated(
					Created(aggregateId, name, accountId, createdAt)
				).update(
					Rename(aggregateId, name.getValue(Locale.en), Locale.de, namedAt), NameTaken(false)
				) shouldBe
					Right.list(Renamed(aggregateId, name.getValue(Locale.en), Locale.de, namedAt))
			}
		}
	}

	"Delete" {
		should("return Deleted event") {
			SurveyAggregate.rehydrated(
				Created(aggregateId, name, accountId, createdAt)
			).update(
				Delete(aggregateId, deletedAt), Stub() // TODO find a way to overload updated to have one arg and to throw an error when projection is invoked
			) shouldBe
				Right.list(Deleted(aggregateId, deletedAt))
		}

		"when Survey already deleted" {
			should("fail with AlreadyDeleted") {
				SurveyAggregate.rehydrated(
					Created(aggregateId, name, accountId, createdAt),
					Deleted(aggregateId, deletedAt)
				).update(
					Delete(aggregateId, deletedAt), Stub()
				) shouldBe
					Left(AlreadyDeleted)
			}
		}
	}

	"Restore" {
		should("fail with NotDeleted") {
			SurveyAggregate.rehydrated(
				Created(aggregateId, name, accountId, createdAt)
			).update(
				Restore(aggregateId, restoredAt), Stub()
			) shouldBe
				Left(NotDeleted)
		}

		"when Survey already deleted" {
			should("return Restored event") {
				SurveyAggregate.rehydrated(
					Created(aggregateId, name, accountId, createdAt),
					Deleted(aggregateId, deletedAt)
				).update(
					Restore(aggregateId, restoredAt), Stub()
				) shouldBe
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
