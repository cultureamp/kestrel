package com.cultureamp.eventsourcing

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe

class SyncProcessorCatchupValidatorTest : DescribeSpec({

    describe("SyncProcessorCatchupValidator") {

        describe("validateProcessorsBeforeSync") {

            context("when no processors provided") {
                it("should return success") {
                    val eventsSequenceStats = MockEventsSequenceStats(100L)
                    val validator = SyncProcessorCatchupValidator<EventMetadata>(eventsSequenceStats)

                    val result = validator.validateProcessorsBeforeSync(emptyList())

                    (result is Right<Unit>) shouldBe true
                }
            }

            context("when all processors are caught up") {
                it("should return success") {
                    val eventsSequenceStats = MockEventsSequenceStats(100L)
                    val bookmarkStore = InMemoryBookmarkStore()

                    // Set up processors at caught-up state
                    bookmarkStore.save(Bookmark("processor1", 100L))
                    bookmarkStore.save(Bookmark("processor2", 98L))

                    val processor1 = BookmarkedEventProcessor.from(
                        bookmarkStore = bookmarkStore,
                        bookmarkName = "processor1",
                        eventProcessor = NoOpEventProcessor()
                    )
                    val processor2 = BookmarkedEventProcessor.from(
                        bookmarkStore = bookmarkStore,
                        bookmarkName = "processor2",
                        eventProcessor = NoOpEventProcessor()
                    )

                    val validator = SyncProcessorCatchupValidator<EventMetadata>(
                        eventsSequenceStats,
                        CatchupValidationConfig(allowableGap = 2)
                    )

                    val result = validator.validateProcessorsBeforeSync(listOf(processor1, processor2))

                    (result is Right<Unit>) shouldBe true
                }
            }

            context("when processor is behind threshold in ENFORCE mode") {
                it("should return validation error") {
                    val eventsSequenceStats = MockEventsSequenceStats(100L)
                    val bookmarkStore = InMemoryBookmarkStore()

                    // Processor behind by 10, tolerance is 5
                    bookmarkStore.save(Bookmark("processor1", 90L))

                    val processor1 = BookmarkedEventProcessor.from(
                        bookmarkStore = bookmarkStore,
                        bookmarkName = "processor1",
                        eventProcessor = NoOpEventProcessor()
                    )

                    val validator = SyncProcessorCatchupValidator<EventMetadata>(
                        eventsSequenceStats,
                        CatchupValidationConfig(
                            validationMode = CatchupValidationMode.ENFORCE,
                            allowableGap = 5
                        )
                    )

                    val result = validator.validateProcessorsBeforeSync(listOf(processor1))

                    (result is Left<SyncCatchupValidationError>) shouldBe true
                    val error = (result as Left).error
                    (error is SyncCatchupValidationFailed) shouldBe true
                }
            }

            context("when processor is behind but in WARN mode") {
                it("should return success and log warning") {
                    val eventsSequenceStats = MockEventsSequenceStats(100L)
                    val bookmarkStore = InMemoryBookmarkStore()

                    bookmarkStore.save(Bookmark("processor1", 90L))

                    val processor1 = BookmarkedEventProcessor.from(
                        bookmarkStore = bookmarkStore,
                        bookmarkName = "processor1",
                        eventProcessor = NoOpEventProcessor()
                    )

                    val validator = SyncProcessorCatchupValidator<EventMetadata>(
                        eventsSequenceStats,
                        CatchupValidationConfig(
                            validationMode = CatchupValidationMode.WARN,
                            allowableGap = 5
                        )
                    )

                    val result = validator.validateProcessorsBeforeSync(listOf(processor1))

                    (result is Right<Unit>) shouldBe true
                }
            }

            context("when processor is behind but in SKIP mode") {
                it("should return success without validation") {
                    val eventsSequenceStats = MockEventsSequenceStats(100L)
                    val bookmarkStore = InMemoryBookmarkStore()

                    val processor1 = BookmarkedEventProcessor.from(
                        bookmarkStore = bookmarkStore,
                        bookmarkName = "processor1",
                        eventProcessor = NoOpEventProcessor()
                    )

                    val validator = SyncProcessorCatchupValidator<EventMetadata>(
                        eventsSequenceStats,
                        CatchupValidationConfig(validationMode = CatchupValidationMode.SKIP)
                    )

                    val result = validator.validateProcessorsBeforeSync(listOf(processor1))

                    (result is Right<Unit>) shouldBe true
                }
            }

            context("when processor is ahead and allowAhead is false") {
                it("should return validation error in ENFORCE mode") {
                    val eventsSequenceStats = MockEventsSequenceStats(100L)
                    val bookmarkStore = InMemoryBookmarkStore()

                    bookmarkStore.save(Bookmark("processor1", 105L))

                    val processor1 = BookmarkedEventProcessor.from(
                        bookmarkStore = bookmarkStore,
                        bookmarkName = "processor1",
                        eventProcessor = NoOpEventProcessor()
                    )

                    val validator = SyncProcessorCatchupValidator<EventMetadata>(
                        eventsSequenceStats,
                        CatchupValidationConfig(
                            validationMode = CatchupValidationMode.ENFORCE,
                            allowAhead = false
                        )
                    )

                    val result = validator.validateProcessorsBeforeSync(listOf(processor1))

                    (result is Left<SyncCatchupValidationError>) shouldBe true
                }
            }

            context("with per-processor validation configs") {
                it("should apply specific config to each processor") {
                    val eventsSequenceStats = MockEventsSequenceStats(100L)
                    val bookmarkStore = InMemoryBookmarkStore()

                    bookmarkStore.save(Bookmark("processor1", 90L))
                    bookmarkStore.save(Bookmark("processor2", 85L))

                    val processor1 = BookmarkedEventProcessor.from(
                        bookmarkStore = bookmarkStore,
                        bookmarkName = "processor1",
                        eventProcessor = NoOpEventProcessor()
                    )
                    val processor2 = BookmarkedEventProcessor.from(
                        bookmarkStore = bookmarkStore,
                        bookmarkName = "processor2",
                        eventProcessor = NoOpEventProcessor()
                    )

                    val validator = SyncProcessorCatchupValidator<EventMetadata>(
                        eventsSequenceStats,
                        CatchupValidationConfig(validationMode = CatchupValidationMode.ENFORCE)
                    )

                    val validationConfigs = mapOf(
                        "processor1" to CatchupValidationConfig(allowableGap = 10), // Should pass
                        "processor2" to CatchupValidationConfig(allowableGap = 15)  // Should pass
                    )

                    val result = validator.validateProcessorsBeforeSync(listOf(processor1, processor2), validationConfigs)

                    (result is Right<Unit>) shouldBe true
                }
            }

            context("when bookmark store throws exception") {
                it("should return validation exception") {
                    val eventsSequenceStats = MockEventsSequenceStats(100L)
                    val bookmarkStore = CompletelyFailingBookmarkStore() // This one fails on bookmarkFor()

                    val processor1 = BookmarkedEventProcessor.from(
                        bookmarkStore = bookmarkStore,
                        bookmarkName = "processor1",
                        eventProcessor = NoOpEventProcessor()
                    )

                    val validator = SyncProcessorCatchupValidator<EventMetadata>(eventsSequenceStats)

                    val result = validator.validateProcessorsBeforeSync(listOf(processor1))

                    (result is Left<SyncCatchupValidationError>) shouldBe true
                    val error = (result as Left).error
                    (error is SyncCatchupValidationException) shouldBe true
                }
            }
        }

        describe("validateProcessorBeforeSync") {
            it("should validate a single processor") {
                val eventsSequenceStats = MockEventsSequenceStats(100L)
                val bookmarkStore = InMemoryBookmarkStore()

                bookmarkStore.save(Bookmark("processor1", 100L))

                val processor1 = BookmarkedEventProcessor.from(
                    bookmarkStore = bookmarkStore,
                    bookmarkName = "processor1",
                    eventProcessor = NoOpEventProcessor()
                )

                val validator = SyncProcessorCatchupValidator<EventMetadata>(eventsSequenceStats)

                val result = validator.validateProcessorBeforeSync(processor1)

                (result is Right<Unit>) shouldBe true
            }
        }

        describe("getValidationStatus") {
            it("should return validation status for all processors") {
                val eventsSequenceStats = MockEventsSequenceStats(100L)
                val bookmarkStore = InMemoryBookmarkStore()

                bookmarkStore.save(Bookmark("processor1", 100L))
                bookmarkStore.save(Bookmark("processor2", 95L))

                val processor1 = BookmarkedEventProcessor.from(
                    bookmarkStore = bookmarkStore,
                    bookmarkName = "processor1",
                    eventProcessor = NoOpEventProcessor()
                )
                val processor2 = BookmarkedEventProcessor.from(
                    bookmarkStore = bookmarkStore,
                    bookmarkName = "processor2",
                    eventProcessor = NoOpEventProcessor()
                )

                val validator = SyncProcessorCatchupValidator<EventMetadata>(eventsSequenceStats)

                val statuses = validator.getValidationStatus(listOf(processor1, processor2))

                statuses.size shouldBe 2
                (statuses["processor1"] is CatchupValid) shouldBe true
                (statuses["processor2"] is CatchupBehind) shouldBe true
            }

            it("should handle exceptions gracefully") {
                val eventsSequenceStats = FailingEventsSequenceStats() // Will throw exception
                val bookmarkStore = InMemoryBookmarkStore()

                val processor1 = BookmarkedEventProcessor.from(
                    bookmarkStore = bookmarkStore,
                    bookmarkName = "processor1",
                    eventProcessor = NoOpEventProcessor()
                )

                val validator = SyncProcessorCatchupValidator<EventMetadata>(eventsSequenceStats)

                val statuses = validator.getValidationStatus(listOf(processor1))

                statuses.size shouldBe 1
                (statuses["processor1"] is CatchupError) shouldBe true
            }
        }
    }
})

// Test implementations
class NoOpEventProcessor : EventProcessor<EventMetadata> {
    override fun process(event: Event<out EventMetadata>, sequence: Long) {
        // No-op
    }
    override fun domainEventClasses() = emptyList<kotlin.reflect.KClass<out DomainEvent>>()
}

class FailingEventProcessor : EventProcessor<EventMetadata> {
    override fun process(event: Event<out EventMetadata>, sequence: Long) {
        throw RuntimeException("Processing failed")
    }
    override fun domainEventClasses() = emptyList<kotlin.reflect.KClass<out DomainEvent>>()
}

class FailingEventsSequenceStats : EventsSequenceStats {
    override fun lastSequence(eventClasses: List<kotlin.reflect.KClass<out DomainEvent>>): Long {
        throw RuntimeException("Database error")
    }

    override fun save(eventClass: kotlin.reflect.KClass<out DomainEvent>, sequence: Long) {
        throw RuntimeException("Database error")
    }
}

class CompletelyFailingBookmarkStore : BookmarkStore {
    override fun bookmarkFor(bookmarkName: String): Bookmark {
        throw RuntimeException("Bookmark store failure")
    }

    override fun bookmarksFor(bookmarkNames: Set<String>): Set<Bookmark> {
        throw RuntimeException("Bookmark store failure")
    }

    override fun save(bookmark: Bookmark) {
        throw RuntimeException("Bookmark store failure")
    }

    override fun checkoutBookmark(bookmarkName: String): Either<LockNotObtained, Bookmark> {
        throw RuntimeException("Bookmark store failure")
    }
}