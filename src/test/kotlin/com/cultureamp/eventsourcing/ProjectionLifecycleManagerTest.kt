package com.cultureamp.eventsourcing

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.beInstanceOf
import io.kotest.matchers.string.shouldContain
import kotlin.reflect.KClass

class ProjectionLifecycleManagerTest : StringSpec({
    "should start projection build with target sequence" {
        val bookmarkStore = InMemoryBookmarkStore().apply {
            save(Bookmark("test_projection", 50))
        }
        val validator = ProjectionCatchupValidator(bookmarkStore)
        val configManager = SyncProcessorConfigurationManager<TestEventMetadata>(validator)
        val lifecycleManager = ProjectionLifecycleManager(validator, configManager)

        val config = ProjectionLifecycleConfig(
            name = "test_projection",
            eventProcessor = TestEventProcessor(),
            bookmarkStore = bookmarkStore,
            targetSequence = 100L
        )

        val result = lifecycleManager.startProjectionBuild(config)

        result should beInstanceOf<ProjectionBuilding>()
        result as ProjectionBuilding
        result.name shouldBe "test_projection"
        result.currentSequence shouldBe 50L
        result.targetSequence shouldBe 100L
        result.progress shouldBe 0.5
    }

    "should detect projection ready for promotion" {
        val bookmarkStore = InMemoryBookmarkStore().apply {
            save(Bookmark("test_projection", 100))
        }
        val validator = ProjectionCatchupValidator(bookmarkStore)
        val configManager = SyncProcessorConfigurationManager<TestEventMetadata>(validator)
        val lifecycleManager = ProjectionLifecycleManager(validator, configManager)

        val config = ProjectionLifecycleConfig(
            name = "test_projection",
            eventProcessor = TestEventProcessor(),
            bookmarkStore = bookmarkStore,
            targetSequence = 100L
        )

        val result = lifecycleManager.startProjectionBuild(config)

        result should beInstanceOf<ProjectionReadyForPromotion>()
        result as ProjectionReadyForPromotion
        result.name shouldBe "test_projection"
        result.currentSequence shouldBe 100L
    }

    "should start projection build with target projection name" {
        val bookmarkStore = InMemoryBookmarkStore().apply {
            save(Bookmark("projection_a", 100))
            save(Bookmark("projection_b", 75))
        }
        val validator = ProjectionCatchupValidator(bookmarkStore)
        val configManager = SyncProcessorConfigurationManager<TestEventMetadata>(validator)
        val lifecycleManager = ProjectionLifecycleManager(validator, configManager)

        val config = ProjectionLifecycleConfig(
            name = "projection_b",
            eventProcessor = TestEventProcessor(),
            bookmarkStore = bookmarkStore
        )

        val result = lifecycleManager.startProjectionBuild(config, "projection_a")

        result should beInstanceOf<ProjectionBuilding>()
        result as ProjectionBuilding
        result.name shouldBe "projection_b"
        result.currentSequence shouldBe 75L
        result.targetSequence shouldBe 100L
        result.progress shouldBe 0.75
    }

    "should get projection status for active projection" {
        val bookmarkStore = InMemoryBookmarkStore().apply {
            save(Bookmark("active_projection", 50))
        }
        val validator = ProjectionCatchupValidator(bookmarkStore)
        val configManager = SyncProcessorConfigurationManager<TestEventMetadata>(validator)
        val lifecycleManager = ProjectionLifecycleManager(validator, configManager)

        val config = ProjectionLifecycleConfig(
            name = "active_projection",
            eventProcessor = TestEventProcessor(),
            bookmarkStore = bookmarkStore
        )

        val result = lifecycleManager.getProjectionStatus(config)

        result should beInstanceOf<ProjectionActive>()
        result as ProjectionActive
        result.name shouldBe "active_projection"
        result.currentSequence shouldBe 50L
    }

    "should monitor multiple projections" {
        val bookmarkStore = InMemoryBookmarkStore().apply {
            save(Bookmark("projection1", 100))
            save(Bookmark("projection2", 50))
            save(Bookmark("projection3", 200))
        }
        val validator = ProjectionCatchupValidator(bookmarkStore)
        val configManager = SyncProcessorConfigurationManager<TestEventMetadata>(validator)
        val lifecycleManager = ProjectionLifecycleManager(validator, configManager)

        val configs = listOf(
            ProjectionLifecycleConfig("projection1", TestEventProcessor(), bookmarkStore),
            ProjectionLifecycleConfig("projection2", TestEventProcessor(), bookmarkStore, targetSequence = 100L),
            ProjectionLifecycleConfig("projection3", TestEventProcessor(), bookmarkStore, targetSequence = 200L)
        )

        val results = lifecycleManager.monitorProjections(configs)

        results.size shouldBe 3
        results["projection1"] should beInstanceOf<ProjectionActive>()
        results["projection2"] should beInstanceOf<ProjectionBuilding>()
        results["projection3"] should beInstanceOf<ProjectionReadyForPromotion>()
    }

    "should execute successful A/B substitution when candidate is ready" {
        val bookmarkStore = InMemoryBookmarkStore().apply {
            save(Bookmark("projection_a", 100))
            save(Bookmark("projection_b", 100))
        }
        val validator = ProjectionCatchupValidator(bookmarkStore)
        val configManager = SyncProcessorConfigurationManager<TestEventMetadata>(validator)
        val lifecycleManager = ProjectionLifecycleManager(validator, configManager)

        val candidateConfig = SyncProcessorConfig("projection_b", TestEventProcessor(), bookmarkStore)

        val result = lifecycleManager.executeABSubstitution("projection_a", "projection_b", candidateConfig)

        result should beInstanceOf<SubstitutionSuccess>()
        result as SubstitutionSuccess
        result.message shouldContain "A/B substitution ready for deployment"
        result.oldProjection shouldBe "projection_a"
        result.newProjection shouldBe "projection_b"
    }

    "should detect A/B substitution not ready when candidate is behind" {
        val bookmarkStore = InMemoryBookmarkStore().apply {
            save(Bookmark("projection_a", 100))
            save(Bookmark("projection_b", 80))
        }
        val validator = ProjectionCatchupValidator(bookmarkStore)
        val configManager = SyncProcessorConfigurationManager<TestEventMetadata>(validator)
        val lifecycleManager = ProjectionLifecycleManager(validator, configManager)

        val candidateConfig = SyncProcessorConfig("projection_b", TestEventProcessor(), bookmarkStore)

        val result = lifecycleManager.executeABSubstitution("projection_a", "projection_b", candidateConfig)

        result should beInstanceOf<SubstitutionError>()
        result as SubstitutionError
        result.error shouldContain "A/B substitution validation failed"
        result.rollbackInstructions.size shouldBe 3
        result.rollbackInstructions[0] shouldContain "Wait for"
    }

    "should plan rollback successfully" {
        val bookmarkStore = InMemoryBookmarkStore()
        val validator = ProjectionCatchupValidator(bookmarkStore)
        val configManager = SyncProcessorConfigurationManager<TestEventMetadata>(validator)
        val lifecycleManager = ProjectionLifecycleManager(validator, configManager)

        val originalConfig = SyncProcessorConfig("projection_a", TestEventProcessor(), bookmarkStore)

        val result = lifecycleManager.planRollback("projection_a", originalConfig)

        result should beInstanceOf<SubstitutionSuccess>()
        result as SubstitutionSuccess
        result.message shouldContain "Rollback plan ready"
        result.message shouldContain "projection_a"
    }

    "should generate comprehensive status report" {
        val bookmarkStore = InMemoryBookmarkStore().apply {
            save(Bookmark("active_proj", 100))
            save(Bookmark("building_proj", 50))
            save(Bookmark("ready_proj", 200))
        }
        val validator = ProjectionCatchupValidator(bookmarkStore)
        val configManager = SyncProcessorConfigurationManager<TestEventMetadata>(validator)
        val lifecycleManager = ProjectionLifecycleManager(validator, configManager)

        val configs = listOf(
            ProjectionLifecycleConfig("active_proj", TestEventProcessor(), bookmarkStore),
            ProjectionLifecycleConfig("building_proj", TestEventProcessor(), bookmarkStore, targetSequence = 100L),
            ProjectionLifecycleConfig("ready_proj", TestEventProcessor(), bookmarkStore, targetSequence = 200L)
        )

        val report = lifecycleManager.generateStatusReport(configs)

        report shouldContain "=== Projection Lifecycle Status Report ==="
        report shouldContain "✅ active_proj: ACTIVE"
        report shouldContain "🏗️ building_proj: BUILDING 50%"
        report shouldContain "🚀 ready_proj: READY FOR PROMOTION"
        report shouldContain "Projections ready for A/B substitution:"
        report shouldContain "- ready_proj"
    }

    "should validate projection health and identify issues" {
        val bookmarkStore = InMemoryBookmarkStore().apply {
            save(Bookmark("healthy_proj", 100))
            save(Bookmark("slow_proj", 5)) // Very low progress
        }
        val validator = ProjectionCatchupValidator(bookmarkStore)
        val configManager = SyncProcessorConfigurationManager<TestEventMetadata>(validator)
        val lifecycleManager = ProjectionLifecycleManager(validator, configManager)

        val configs = listOf(
            ProjectionLifecycleConfig("healthy_proj", TestEventProcessor(), bookmarkStore),
            ProjectionLifecycleConfig("slow_proj", TestEventProcessor(), bookmarkStore, targetSequence = 100L)
        )

        val issues = lifecycleManager.validateProjectionHealth(configs)

        issues.size shouldBe 1
        issues[0] shouldContain "slow_proj"
        issues[0] shouldContain "build progress is very low"
        issues[0] shouldContain "5%"
    }

    "should validate projection health with no issues for healthy projections" {
        val bookmarkStore = InMemoryBookmarkStore().apply {
            save(Bookmark("healthy_proj1", 100))
            save(Bookmark("healthy_proj2", 80))
        }
        val validator = ProjectionCatchupValidator(bookmarkStore)
        val configManager = SyncProcessorConfigurationManager<TestEventMetadata>(validator)
        val lifecycleManager = ProjectionLifecycleManager(validator, configManager)

        val configs = listOf(
            ProjectionLifecycleConfig("healthy_proj1", TestEventProcessor(), bookmarkStore),
            ProjectionLifecycleConfig("healthy_proj2", TestEventProcessor(), bookmarkStore, targetSequence = 100L)
        )

        val issues = lifecycleManager.validateProjectionHealth(configs)

        issues.size shouldBe 0
    }
})

// Uses TestEventProcessor, TestEventMetadata, and InMemoryBookmarkStore from other test files