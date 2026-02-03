package com.cultureamp.eventsourcing

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.beInstanceOf

class ProjectionCatchupValidatorTest : StringSpec({
    "should validate projection is caught up when sequences match exactly" {
        val bookmarkStore = InMemoryBookmarkStore().apply {
            save(Bookmark("projection_a", 100))
        }
        val validator = ProjectionCatchupValidator(bookmarkStore)

        val result = validator.validateCatchup("projection_a", 100)

        result should beInstanceOf<CatchupValid>()
        result as CatchupValid
        result.projectionName shouldBe "projection_a"
        result.sequence shouldBe 100
    }

    "should validate projection is caught up when within allowable gap" {
        val bookmarkStore = InMemoryBookmarkStore().apply {
            save(Bookmark("projection_a", 98))
        }
        val config = CatchupValidationConfig(allowableGap = 2)
        val validator = ProjectionCatchupValidator(bookmarkStore, config)

        val result = validator.validateCatchup("projection_a", 100)

        result should beInstanceOf<CatchupValid>()
        result as CatchupValid
        result.projectionName shouldBe "projection_a"
        result.sequence shouldBe 98
    }

    "should detect projection is behind when gap exceeds allowable" {
        val bookmarkStore = InMemoryBookmarkStore().apply {
            save(Bookmark("projection_a", 95))
        }
        val validator = ProjectionCatchupValidator(bookmarkStore)

        val result = validator.validateCatchup("projection_a", 100)

        result should beInstanceOf<CatchupBehind>()
        result as CatchupBehind
        result.projectionName shouldBe "projection_a"
        result.currentSequence shouldBe 95
        result.targetSequence shouldBe 100
        result.gap shouldBe 5
    }

    "should allow projection to be ahead by default" {
        val bookmarkStore = InMemoryBookmarkStore().apply {
            save(Bookmark("projection_a", 105))
        }
        val validator = ProjectionCatchupValidator(bookmarkStore)

        val result = validator.validateCatchup("projection_a", 100)

        result should beInstanceOf<CatchupValid>()
        result as CatchupValid
        result.projectionName shouldBe "projection_a"
        result.sequence shouldBe 105
    }

    "should detect projection is ahead when not allowed" {
        val bookmarkStore = InMemoryBookmarkStore().apply {
            save(Bookmark("projection_a", 105))
        }
        val config = CatchupValidationConfig(allowAhead = false)
        val validator = ProjectionCatchupValidator(bookmarkStore, config)

        val result = validator.validateCatchup("projection_a", 100)

        result should beInstanceOf<CatchupAhead>()
        result as CatchupAhead
        result.projectionName shouldBe "projection_a"
        result.currentSequence shouldBe 105
        result.targetSequence shouldBe 100
        result.gap shouldBe 5
    }

    "should validate catch-up to another projection" {
        val bookmarkStore = InMemoryBookmarkStore().apply {
            save(Bookmark("projection_a", 100))
            save(Bookmark("projection_b", 100))
        }
        val validator = ProjectionCatchupValidator(bookmarkStore)

        val result = validator.validateCatchupToProjection("projection_b", "projection_a")

        result should beInstanceOf<CatchupValid>()
        result as CatchupValid
        result.projectionName shouldBe "projection_b"
        result.sequence shouldBe 100
    }

    "should detect when candidate projection is behind target projection" {
        val bookmarkStore = InMemoryBookmarkStore().apply {
            save(Bookmark("projection_a", 100))
            save(Bookmark("projection_b", 95))
        }
        val validator = ProjectionCatchupValidator(bookmarkStore)

        val result = validator.validateCatchupToProjection("projection_b", "projection_a")

        result should beInstanceOf<CatchupBehind>()
        result as CatchupBehind
        result.projectionName shouldBe "projection_b"
        result.currentSequence shouldBe 95
        result.targetSequence shouldBe 100
        result.gap shouldBe 5
    }

    "should handle errors when projection does not exist" {
        val bookmarkStore = InMemoryBookmarkStore()
        val validator = ProjectionCatchupValidator(bookmarkStore)

        // InMemoryBookmarkStore returns Bookmark with sequence 0 for non-existent bookmarks
        val result = validator.validateCatchup("nonexistent", 100)

        result should beInstanceOf<CatchupBehind>()
        result as CatchupBehind
        result.projectionName shouldBe "nonexistent"
        result.currentSequence shouldBe 0
        result.targetSequence shouldBe 100
        result.gap shouldBe 100
    }

    "should validate multiple projections against individual targets" {
        val bookmarkStore = InMemoryBookmarkStore().apply {
            save(Bookmark("proj1", 100))
            save(Bookmark("proj2", 95))
            save(Bookmark("proj3", 105))
        }
        val validator = ProjectionCatchupValidator(bookmarkStore)

        val results = validator.validateMultipleCatchups(mapOf(
            "proj1" to 100L,
            "proj2" to 100L,
            "proj3" to 100L
        ))

        results["proj1"] should beInstanceOf<CatchupValid>()
        results["proj2"] should beInstanceOf<CatchupBehind>()
        results["proj3"] should beInstanceOf<CatchupValid>()
    }

    "should validate multiple projections against common target" {
        val bookmarkStore = InMemoryBookmarkStore().apply {
            save(Bookmark("proj1", 100))
            save(Bookmark("proj2", 98))
        }
        val config = CatchupValidationConfig(allowableGap = 2)
        val validator = ProjectionCatchupValidator(bookmarkStore, config)

        val results = validator.validateMultipleCatchupsToSequence(listOf("proj1", "proj2"), 100)

        results["proj1"] should beInstanceOf<CatchupValid>()
        results["proj2"] should beInstanceOf<CatchupValid>()
        validator.allCaughtUp(results.values) shouldBe true
    }

    "should detect when not all projections are caught up" {
        val bookmarkStore = InMemoryBookmarkStore().apply {
            save(Bookmark("proj1", 100))
            save(Bookmark("proj2", 90))
        }
        val validator = ProjectionCatchupValidator(bookmarkStore)

        val results = validator.validateMultipleCatchupsToSequence(listOf("proj1", "proj2"), 100)

        results["proj1"] should beInstanceOf<CatchupValid>()
        results["proj2"] should beInstanceOf<CatchupBehind>()
        validator.allCaughtUp(results.values) shouldBe false
    }

    "should provide meaningful summary of validation results" {
        val results = listOf(
            CatchupValid("proj1", 100),
            CatchupBehind("proj2", 95, 100, 5),
            CatchupAhead("proj3", 105, 100, 5),
            CatchupError("proj4", "Connection failed")
        )
        val validator = ProjectionCatchupValidator(InMemoryBookmarkStore())

        val summary = validator.summarizeResults(results)

        summary shouldBe "Projection catch-up validation: 1/4 valid, 1 behind, 1 ahead, 1 errors"
    }
})

// Uses InMemoryBookmarkStore from BlockingSyncEventProcessorTest.kt