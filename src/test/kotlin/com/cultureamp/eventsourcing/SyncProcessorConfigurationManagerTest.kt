package com.cultureamp.eventsourcing

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.beInstanceOf
import io.kotest.matchers.collections.contain
import io.kotest.matchers.string.shouldContain
import kotlin.reflect.KClass

class SyncProcessorConfigurationManagerTest : StringSpec({
    "should validate configuration successfully with no conflicts" {
        val bookmarkStore = InMemoryBookmarkStore()
        val validator = ProjectionCatchupValidator(bookmarkStore)
        val manager = SyncProcessorConfigurationManager<TestEventMetadata>(validator)

        val config1 = SyncProcessorConfig("processor1", TestEventProcessor(), bookmarkStore)
        val config2 = SyncProcessorConfig("processor2", TestEventProcessor(), bookmarkStore)

        val result = manager.validateConfiguration(listOf(config1, config2))

        result should beInstanceOf<ConfigurationChangeSuccess>()
        result as ConfigurationChangeSuccess
        result.message shouldContain "Configuration validation passed for 2 processors"
    }

    "should detect duplicate processor names" {
        val bookmarkStore = InMemoryBookmarkStore()
        val validator = ProjectionCatchupValidator(bookmarkStore)
        val manager = SyncProcessorConfigurationManager<TestEventMetadata>(validator)

        val config1 = SyncProcessorConfig("processor1", TestEventProcessor(), bookmarkStore)
        val config2 = SyncProcessorConfig("processor1", TestEventProcessor(), bookmarkStore) // duplicate name

        val result = manager.validateConfiguration(listOf(config1, config2))

        result should beInstanceOf<ConfigurationValidationError>()
        result as ConfigurationValidationError
        result.validationErrors should contain("Duplicate processor names found: processor1")
    }

    "should warn about inactive processors" {
        val bookmarkStore = InMemoryBookmarkStore()
        val validator = ProjectionCatchupValidator(bookmarkStore)
        val manager = SyncProcessorConfigurationManager<TestEventMetadata>(validator)

        val config1 = SyncProcessorConfig("processor1", TestEventProcessor(), bookmarkStore, isActive = true)
        val config2 = SyncProcessorConfig("processor2", TestEventProcessor(), bookmarkStore, isActive = false)

        val result = manager.validateConfiguration(listOf(config1, config2))

        result should beInstanceOf<ConfigurationValidationError>()
        result as ConfigurationValidationError
        result.validationErrors.size shouldBe 1
        result.validationErrors[0] shouldContain "Inactive processors found"
        result.validationErrors[0] shouldContain "processor2"
    }

    "should validate catch-up requirements" {
        val bookmarkStore = InMemoryBookmarkStore().apply {
            save(Bookmark("processor1", 95)) // behind
            save(Bookmark("processor2", 100)) // caught up
        }
        val validator = ProjectionCatchupValidator(bookmarkStore)
        val manager = SyncProcessorConfigurationManager<TestEventMetadata>(validator)

        val config1 = SyncProcessorConfig("processor1", TestEventProcessor(), bookmarkStore)
        val config2 = SyncProcessorConfig("processor2", TestEventProcessor(), bookmarkStore)

        val catchupRequirements = mapOf("processor1" to 100L, "processor2" to 100L)
        val result = manager.validateConfiguration(listOf(config1, config2), catchupRequirements)

        result should beInstanceOf<ConfigurationValidationError>()
        result as ConfigurationValidationError
        result.validationErrors.size shouldBe 1
        result.validationErrors[0] shouldContain "processor1' is behind"
        result.validationErrors[0] shouldContain "95/100"
    }

    "should create sync processor successfully" {
        val bookmarkStore = InMemoryBookmarkStore()
        val validator = ProjectionCatchupValidator(bookmarkStore)
        val manager = SyncProcessorConfigurationManager<TestEventMetadata>(validator)

        val config1 = SyncProcessorConfig("processor1", TestEventProcessor(), bookmarkStore)
        val config2 = SyncProcessorConfig("processor2", TestEventProcessor(), bookmarkStore)

        val result = manager.createSyncProcessor(listOf(config1, config2))

        result should beInstanceOf<Right<*>>()
        result as Right<BlockingSyncEventProcessor<TestEventMetadata>>
        // Processor should be created successfully
    }

    "should filter out inactive processors when creating sync processor" {
        val bookmarkStore = InMemoryBookmarkStore()
        val validator = ProjectionCatchupValidator(bookmarkStore)
        val manager = SyncProcessorConfigurationManager<TestEventMetadata>(validator)

        val config1 = SyncProcessorConfig("processor1", TestEventProcessor(), bookmarkStore, isActive = true)
        val config2 = SyncProcessorConfig("processor2", TestEventProcessor(), bookmarkStore, isActive = false)

        val result = manager.createSyncProcessor(listOf(config1, config2))

        result should beInstanceOf<Right<*>>()
        // Should create processor with only the active configuration
    }

    "should fail to create sync processor with no active configurations" {
        val bookmarkStore = InMemoryBookmarkStore()
        val validator = ProjectionCatchupValidator(bookmarkStore)
        val manager = SyncProcessorConfigurationManager<TestEventMetadata>(validator)

        val config1 = SyncProcessorConfig("processor1", TestEventProcessor(), bookmarkStore, isActive = false)

        val result = manager.createSyncProcessor(listOf(config1))

        result should beInstanceOf<Left<*>>()
        result as Left<ConfigurationChangeError>
        result.error.error shouldContain "No active processors found"
    }

    "should plan A/B substitution when candidate is caught up" {
        val bookmarkStore = InMemoryBookmarkStore().apply {
            save(Bookmark("projection_a", 100))
            save(Bookmark("projection_b", 100))
        }
        val validator = ProjectionCatchupValidator(bookmarkStore)
        val manager = SyncProcessorConfigurationManager<TestEventMetadata>(validator)

        val result = manager.planABSubstitution("projection_a", "projection_b")

        result should beInstanceOf<ConfigurationChangeSuccess>()
        result as ConfigurationChangeSuccess
        result.message shouldContain "A/B substitution ready"
        result.message shouldContain "projection_b"
        result.message shouldContain "can replace"
    }

    "should detect when A/B substitution is not ready" {
        val bookmarkStore = InMemoryBookmarkStore().apply {
            save(Bookmark("projection_a", 100))
            save(Bookmark("projection_b", 95))
        }
        val validator = ProjectionCatchupValidator(bookmarkStore)
        val manager = SyncProcessorConfigurationManager<TestEventMetadata>(validator)

        val result = manager.planABSubstitution("projection_a", "projection_b")

        result should beInstanceOf<ConfigurationChangeError>()
        result as ConfigurationChangeError
        result.error shouldContain "not ready"
        result.error shouldContain "behind by 5 events"
    }

    "should allow A/B substitution when candidate is ahead" {
        val bookmarkStore = InMemoryBookmarkStore().apply {
            save(Bookmark("projection_a", 100))
            save(Bookmark("projection_b", 105))
        }
        val validator = ProjectionCatchupValidator(bookmarkStore)
        val manager = SyncProcessorConfigurationManager<TestEventMetadata>(validator)

        val result = manager.planABSubstitution("projection_a", "projection_b")

        result should beInstanceOf<ConfigurationChangeSuccess>()
        result as ConfigurationChangeSuccess
        result.message shouldContain "A/B substitution ready"
        result.message shouldContain "can replace"
        result.message shouldContain "Deploy with new sync processor configuration"
    }

    "should detect A/B substitution with explicit ahead behavior" {
        val bookmarkStore = InMemoryBookmarkStore().apply {
            save(Bookmark("projection_a", 100))
            save(Bookmark("projection_b", 105))
        }
        val config = CatchupValidationConfig(allowAhead = false)
        val validator = ProjectionCatchupValidator(bookmarkStore, config)
        val manager = SyncProcessorConfigurationManager<TestEventMetadata>(validator)

        val result = manager.planABSubstitution("projection_a", "projection_b")

        result should beInstanceOf<ConfigurationChangeSuccess>()
        result as ConfigurationChangeSuccess
        result.message shouldContain "A/B substitution ready"
        result.message shouldContain "is ahead of"
        result.message shouldContain "Safe to deploy"
    }

    "should generate comprehensive deployment checklist" {
        val bookmarkStore = InMemoryBookmarkStore()
        val validator = ProjectionCatchupValidator(bookmarkStore)
        val manager = SyncProcessorConfigurationManager<TestEventMetadata>(validator)

        val checklist = manager.generateDeploymentChecklist("projection_a", "projection_b")

        checklist.size shouldBe 8
        checklist[0] shouldContain "Validate catch-up status"
        checklist[1] shouldContain "Test candidate processor"
        checklist[2] shouldContain "Prepare new configuration"
        checklist[3] shouldContain "Update EventStore hook"
        checklist[4] shouldContain "Monitor deployment"
        checklist[5] shouldContain "Validate deployment"
        checklist[6] shouldContain "Monitor performance"
        checklist[7] shouldContain "Cleanup"
    }

    "should include additional processors in deployment checklist" {
        val bookmarkStore = InMemoryBookmarkStore()
        val validator = ProjectionCatchupValidator(bookmarkStore)
        val manager = SyncProcessorConfigurationManager<TestEventMetadata>(validator)

        val checklist = manager.generateDeploymentChecklist(
            "projection_a",
            "projection_b",
            listOf("other_processor1", "other_processor2")
        )

        checklist.size shouldBe 8
        checklist[4] shouldContain "Coordinate additional processors"
        checklist[4] shouldContain "other_processor1, other_processor2"
    }

    "should create rollback plan" {
        val bookmarkStore = InMemoryBookmarkStore()
        val validator = ProjectionCatchupValidator(bookmarkStore)
        val manager = SyncProcessorConfigurationManager<TestEventMetadata>(validator)

        val originalConfig = listOf(
            SyncProcessorConfig("processor1", TestEventProcessor(), bookmarkStore),
            SyncProcessorConfig("processor2", TestEventProcessor(), bookmarkStore, isActive = false)
        )

        val result = manager.createRollbackPlan(originalConfig)

        result should beInstanceOf<ConfigurationChangeSuccess>()
        result as ConfigurationChangeSuccess
        result.message shouldContain "Rollback plan ready"
        result.message shouldContain "1 original processors"
        result.message shouldContain "processor1"
    }

    "should handle empty rollback configuration" {
        val bookmarkStore = InMemoryBookmarkStore()
        val validator = ProjectionCatchupValidator(bookmarkStore)
        val manager = SyncProcessorConfigurationManager<TestEventMetadata>(validator)

        val result = manager.createRollbackPlan(emptyList())

        result should beInstanceOf<ConfigurationChangeError>()
        result as ConfigurationChangeError
        result.error shouldContain "No original configuration provided"
    }
})

// Test helper classes - made public for reuse in other tests
internal class TestEventProcessor : EventProcessor<TestEventMetadata> {
    override fun process(event: Event<out TestEventMetadata>, sequence: Long) {
        // No-op for testing
    }

    override fun domainEventClasses(): List<KClass<out DomainEvent>> = emptyList()
}

internal class TestEventMetadata : EventMetadata()

// Uses InMemoryBookmarkStore from BlockingSyncEventProcessorTest.kt