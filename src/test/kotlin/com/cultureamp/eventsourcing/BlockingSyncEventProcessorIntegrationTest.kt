package com.cultureamp.eventsourcing

import com.cultureamp.eventsourcing.example.AlwaysBoppable
import com.cultureamp.eventsourcing.example.Boop
import com.cultureamp.eventsourcing.example.CreateSimpleThing
import com.cultureamp.eventsourcing.example.CreateThing
import com.cultureamp.eventsourcing.example.SimpleThingAggregate
import com.cultureamp.eventsourcing.example.SimpleThingCreated
import com.cultureamp.eventsourcing.example.SimpleThingEvent
import com.cultureamp.eventsourcing.example.ThingAggregate
import com.cultureamp.eventsourcing.example.ThingCreated
import com.cultureamp.eventsourcing.example.Tweak
import com.cultureamp.eventsourcing.sample.CreateClassicPizza
import com.cultureamp.eventsourcing.sample.PizzaAggregate
import com.cultureamp.eventsourcing.sample.PizzaCreated
import com.cultureamp.eventsourcing.sample.PizzaEvent
import com.cultureamp.eventsourcing.sample.PizzaStyle
import com.cultureamp.eventsourcing.sample.StandardEventMetadata
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID

/**
 * Integration tests for BlockingSyncEventProcessor working with RelationalDatabaseEventStore hooks.
 * These tests verify end-to-end functionality of synchronous event processing within database transactions.
 */
class BlockingSyncEventProcessorIntegrationTest : DescribeSpec({
    val db = PgTestConfig.db ?: Database.connect(url = "jdbc:h2:mem:test;MODE=MySQL;DB_CLOSE_DELAY=-1;", driver = "org.h2.Driver")
    val table = Events()
    val tableH2 = Events(jsonb = Table::text)
    val eventsTable = if (PgTestConfig.db != null) table else tableH2
    val eventsSequenceStats = RelationalDatabaseEventsSequenceStats(db)
    val bookmarkStore = RelationalDatabaseBookmarkStore(db)

    beforeTest {
        transaction(db) {
            SchemaUtils.create(eventsTable)
            SchemaUtils.create(eventsSequenceStats.table)
            SchemaUtils.create(bookmarkStore.table)
        }
    }

    afterTest {
        transaction(db) {
            SchemaUtils.drop(eventsTable)
            SchemaUtils.drop(eventsSequenceStats.table)
            SchemaUtils.drop(bookmarkStore.table)
        }
    }

    val accountId = UUID.randomUUID()
    val executorId = UUID.randomUUID()
    val metadata = StandardEventMetadata(accountId = accountId, executorId = executorId)

    describe("BlockingSyncEventProcessor Integration") {
        it("processes events synchronously within EventStore sink transaction") {
            // Create a simple projector that tracks processed events
            val processedEvents = mutableListOf<PizzaEvent>()
            val projector = EventProcessor.from<PizzaEvent, StandardEventMetadata> { event, aggregateId, metadata, eventId, sequence ->
                processedEvents.add(event)
            }
            val bookmarkedProjector = BookmarkedEventProcessor.from(bookmarkStore, "pizza-sync-projector", projector)
            val syncProcessor = BlockingSyncEventProcessor(listOf(bookmarkedProjector))

            // Create EventStore with sync processor in endOfSinkTransactionHook
            var hookCalled = false
            var hookSequencedEvents: List<SequencedEvent<StandardEventMetadata>> = emptyList()
            val eventStore = RelationalDatabaseEventStore.create(
                db,
                eventsSequenceStats = eventsSequenceStats,
                endOfSinkTransactionHook = { sequencedEvents ->
                    hookCalled = true
                    hookSequencedEvents = sequencedEvents
                    syncProcessor.processEvents(sequencedEvents).fold(
                        { error -> throw RuntimeException("Sync processor failed: $error") },
                        { /* success */ }
                    )
                }
            )

            // Create CommandGateway
            val gateway = EventStoreCommandGateway(
                eventStore,
                Route.from(
                    PizzaAggregate.Companion::create,
                    PizzaAggregate::update,
                    ::PizzaAggregate,
                    PizzaAggregate::updated,
                    PizzaAggregate.Companion::aggregateType,
                )
            )

            // Dispatch command
            val pizzaId = UUID.randomUUID()
            val result = gateway.dispatch(CreateClassicPizza(pizzaId, PizzaStyle.MARGHERITA), metadata)

            // Verify command succeeded
            result shouldBe Right(Created)

            // Verify event was persisted
            transaction(db) {
                eventsTable.selectAll().count() shouldBe 1
            }

            // Verify hook was called with sequenced events
            hookCalled shouldBe true
            hookSequencedEvents.size shouldBe 1
            hookSequencedEvents[0].sequence shouldBe 1L
            hookSequencedEvents[0].event.domainEvent.shouldBeInstanceOf<PizzaCreated>()

            // Verify sync processor processed the event
            processedEvents.size shouldBe 1
            processedEvents[0].shouldBeInstanceOf<PizzaCreated>()

            // Verify bookmark was updated within the transaction
            bookmarkStore.bookmarkFor("pizza-sync-projector").sequence shouldBe 1L
        }

        it("rolls back transaction when sync processor fails") {
            // Create a projector that always fails
            val failingProjector = EventProcessor.from<PizzaEvent, StandardEventMetadata> { _, _, _, _, _ ->
                throw RuntimeException("Simulated projector failure")
            }
            val bookmarkedProjector = BookmarkedEventProcessor.from(bookmarkStore, "failing-projector", failingProjector)
            val syncProcessor = BlockingSyncEventProcessor(listOf(bookmarkedProjector))

            // Create EventStore with failing sync processor
            val eventStore = RelationalDatabaseEventStore.create(
                db,
                eventsSequenceStats = eventsSequenceStats,
                endOfSinkTransactionHook = { sequencedEvents ->
                    syncProcessor.processEvents(sequencedEvents).fold(
                        { error -> throw RuntimeException("Sync processor failed: $error") },
                        { /* success */ }
                    )
                }
            )

            val gateway = EventStoreCommandGateway(
                eventStore,
                Route.from(
                    PizzaAggregate.Companion::create,
                    PizzaAggregate::update,
                    ::PizzaAggregate,
                    PizzaAggregate::updated,
                    PizzaAggregate.Companion::aggregateType,
                )
            )

            // Attempt to dispatch command - should fail
            val pizzaId = UUID.randomUUID()
            val result = runCatching {
                gateway.dispatch(CreateClassicPizza(pizzaId, PizzaStyle.MARGHERITA), metadata)
            }

            // Verify command failed due to sync processor failure
            result.isFailure shouldBe true
            result.exceptionOrNull()?.message?.contains("Sync processor failed") shouldBe true

            // Verify no events were persisted due to transaction rollback
            transaction(db) {
                eventsTable.selectAll().count() shouldBe 0
            }

            // Verify no bookmark was saved
            bookmarkStore.bookmarkFor("failing-projector").sequence shouldBe 0L
        }

        it("processes multiple events in sequence with bookmark consistency") {
            val processedEvents = mutableListOf<Pair<String, Long>>()

            // Create projector that tracks event type and sequence
            val projector = EventProcessor.from<PizzaEvent, StandardEventMetadata> { event, aggregateId, metadata, eventId, sequence ->
                processedEvents.add(event::class.simpleName!! to sequence)
            }
            val bookmarkedProjector = BookmarkedEventProcessor.from(bookmarkStore, "multi-event-projector", projector)
            val syncProcessor = BlockingSyncEventProcessor(listOf(bookmarkedProjector))

            val eventStore = RelationalDatabaseEventStore.create(
                db,
                eventsSequenceStats = eventsSequenceStats,
                endOfSinkTransactionHook = { sequencedEvents ->
                    syncProcessor.processEvents(sequencedEvents).fold(
                        { error -> throw RuntimeException("Sync processor failed: $error") },
                        { /* success */ }
                    )
                }
            )

            val gateway = EventStoreCommandGateway(
                eventStore,
                Route.from(
                    PizzaAggregate.Companion::create,
                    PizzaAggregate::update,
                    ::PizzaAggregate,
                    PizzaAggregate::updated,
                    PizzaAggregate.Companion::aggregateType,
                )
            )

            // Create multiple pizzas to generate multiple events
            val pizza1Id = UUID.randomUUID()
            val pizza2Id = UUID.randomUUID()

            gateway.dispatch(CreateClassicPizza(pizza1Id, PizzaStyle.MARGHERITA), metadata)
            gateway.dispatch(CreateClassicPizza(pizza2Id, PizzaStyle.HAWAIIAN), metadata)

            // Verify both events were processed in sequence
            processedEvents.size shouldBe 2
            processedEvents[0] shouldBe ("PizzaCreated" to 1L)
            processedEvents[1] shouldBe ("PizzaCreated" to 2L)

            // Verify final bookmark reflects both events processed
            bookmarkStore.bookmarkFor("multi-event-projector").sequence shouldBe 2L

            // Verify events persisted correctly
            transaction(db) {
                eventsTable.selectAll().count() shouldBe 2
            }
        }

        it("handles multiple sync processors in parallel") {
            val processor1Events = mutableListOf<String>()
            val processor2Events = mutableListOf<String>()

            // Create two different projectors
            val projector1 = EventProcessor.from<PizzaEvent, StandardEventMetadata> { event, _, _, _, _ ->
                processor1Events.add("p1:${event::class.simpleName}")
            }
            val projector2 = EventProcessor.from<PizzaEvent, StandardEventMetadata> { event, _, _, _, _ ->
                processor2Events.add("p2:${event::class.simpleName}")
            }

            val bookmarkedProjector1 = BookmarkedEventProcessor.from(bookmarkStore, "projector-1", projector1)
            val bookmarkedProjector2 = BookmarkedEventProcessor.from(bookmarkStore, "projector-2", projector2)
            val syncProcessor = BlockingSyncEventProcessor(listOf(bookmarkedProjector1, bookmarkedProjector2))

            val eventStore = RelationalDatabaseEventStore.create(
                db,
                eventsSequenceStats = eventsSequenceStats,
                endOfSinkTransactionHook = { sequencedEvents ->
                    syncProcessor.processEvents(sequencedEvents).fold(
                        { error -> throw RuntimeException("Sync processor failed: $error") },
                        { /* success */ }
                    )
                }
            )

            val gateway = EventStoreCommandGateway(
                eventStore,
                Route.from(
                    PizzaAggregate.Companion::create,
                    PizzaAggregate::update,
                    ::PizzaAggregate,
                    PizzaAggregate::updated,
                    PizzaAggregate.Companion::aggregateType,
                )
            )

            // Dispatch command
            val pizzaId = UUID.randomUUID()
            gateway.dispatch(CreateClassicPizza(pizzaId, PizzaStyle.MARGHERITA), metadata)

            // Verify both processors handled the event
            processor1Events shouldBe listOf("p1:PizzaCreated")
            processor2Events shouldBe listOf("p2:PizzaCreated")

            // Verify both processors have their bookmarks updated
            bookmarkStore.bookmarkFor("projector-1").sequence shouldBe 1L
            bookmarkStore.bookmarkFor("projector-2").sequence shouldBe 1L
        }

        it("skips already processed events using bookmarks") {
            val processedEvents = mutableListOf<Long>()

            // Create projector that tracks sequence numbers
            val projector = EventProcessor.from<PizzaEvent, StandardEventMetadata> { event, aggregateId, metadata, eventId, sequence ->
                processedEvents.add(sequence)
            }
            val bookmarkedProjector = BookmarkedEventProcessor.from(bookmarkStore, "bookmark-aware-projector", projector)

            // Manually set bookmark to simulate partially processed state
            bookmarkStore.save(Bookmark("bookmark-aware-projector", 1L))

            val syncProcessor = BlockingSyncEventProcessor(listOf(bookmarkedProjector))

            val eventStore = RelationalDatabaseEventStore.create(
                db,
                eventsSequenceStats = eventsSequenceStats,
                endOfSinkTransactionHook = { sequencedEvents ->
                    syncProcessor.processEvents(sequencedEvents).fold(
                        { error -> throw RuntimeException("Sync processor failed: $error") },
                        { /* success */ }
                    )
                }
            )

            val gateway = EventStoreCommandGateway(
                eventStore,
                Route.from(
                    PizzaAggregate.Companion::create,
                    PizzaAggregate::update,
                    ::PizzaAggregate,
                    PizzaAggregate::updated,
                    PizzaAggregate.Companion::aggregateType,
                )
            )

            // Create pizza - this should be sequence 1, but already processed based on bookmark
            val pizza1Id = UUID.randomUUID()
            gateway.dispatch(CreateClassicPizza(pizza1Id, PizzaStyle.MARGHERITA), metadata)

            // Should not process because bookmark is at 1L already
            processedEvents shouldBe emptyList()
            bookmarkStore.bookmarkFor("bookmark-aware-projector").sequence shouldBe 1L

            // Create another pizza - this should be sequence 2 and should be processed
            val pizza2Id = UUID.randomUUID()
            gateway.dispatch(CreateClassicPizza(pizza2Id, PizzaStyle.HAWAIIAN), metadata)

            // Should process only the new event (sequence 2)
            processedEvents shouldBe listOf(2L)
            bookmarkStore.bookmarkFor("bookmark-aware-projector").sequence shouldBe 2L
        }

        it("works with mixed aggregate types") {
            val allProcessedEvents = mutableListOf<String>()

            // Create catch-all projector (empty domainEventClasses means process all events)
            val projector = EventProcessor.from<DomainEvent, StandardEventMetadata> { event, aggregateId, metadata, eventId, sequence ->
                allProcessedEvents.add("${event::class.simpleName}")
            }
            val bookmarkedProjector = BookmarkedEventProcessor.from(bookmarkStore, "catch-all-projector", projector)
            val syncProcessor = BlockingSyncEventProcessor(listOf(bookmarkedProjector))

            val eventStore = RelationalDatabaseEventStore.create(
                db,
                eventsSequenceStats = eventsSequenceStats,
                endOfSinkTransactionHook = { sequencedEvents ->
                    syncProcessor.processEvents(sequencedEvents).fold(
                        { error -> throw RuntimeException("Sync processor failed: $error") },
                        { /* success */ }
                    )
                }
            )

            val gateway = EventStoreCommandGateway(
                eventStore,
                Route.from(
                    PizzaAggregate.Companion::create,
                    PizzaAggregate::update,
                    ::PizzaAggregate,
                    PizzaAggregate::updated,
                    PizzaAggregate.Companion::aggregateType,
                ),
                Route.from(SimpleThingAggregate)
            )

            // Create events from different aggregate types
            val pizzaId = UUID.randomUUID()
            val thingId = UUID.randomUUID()

            gateway.dispatch(CreateClassicPizza(pizzaId, PizzaStyle.MARGHERITA), metadata)
            gateway.dispatch(CreateSimpleThing(thingId), metadata)

            // Verify both event types were processed by catch-all projector
            allProcessedEvents shouldBe listOf("PizzaCreated", "SimpleThingCreated")
            bookmarkStore.bookmarkFor("catch-all-projector").sequence shouldBe 2L
        }

        it("propagates errors correctly and maintains transaction isolation") {
            // Create one successful and one failing processor
            val successfulEvents = mutableListOf<String>()
            val successfulProjector = EventProcessor.from<PizzaEvent, StandardEventMetadata> { event, _, _, _, _ ->
                successfulEvents.add("success:${event::class.simpleName}")
            }

            val failingProjector = EventProcessor.from<PizzaEvent, StandardEventMetadata> { _, _, _, _, _ ->
                throw RuntimeException("Projector intentionally failed")
            }

            val bookmarkedSuccessful = BookmarkedEventProcessor.from(bookmarkStore, "successful-projector", successfulProjector)
            val bookmarkedFailing = BookmarkedEventProcessor.from(bookmarkStore, "failing-projector", failingProjector)
            val syncProcessor = BlockingSyncEventProcessor(listOf(bookmarkedSuccessful, bookmarkedFailing))

            val eventStore = RelationalDatabaseEventStore.create(
                db,
                eventsSequenceStats = eventsSequenceStats,
                endOfSinkTransactionHook = { sequencedEvents ->
                    syncProcessor.processEvents(sequencedEvents).fold(
                        { error -> throw RuntimeException("Sync processor failed: $error") },
                        { /* success */ }
                    )
                }
            )

            val gateway = EventStoreCommandGateway(
                eventStore,
                Route.from(
                    PizzaAggregate.Companion::create,
                    PizzaAggregate::update,
                    ::PizzaAggregate,
                    PizzaAggregate::updated,
                    PizzaAggregate.Companion::aggregateType,
                )
            )

            // Attempt to dispatch command
            val result = runCatching {
                gateway.dispatch(CreateClassicPizza(UUID.randomUUID(), PizzaStyle.MARGHERITA), metadata)
            }

            // Verify command failed
            result.isFailure shouldBe true

            // Verify transaction was rolled back completely
            transaction(db) {
                eventsTable.selectAll().count() shouldBe 0
            }

            // Verify that in-memory side effects occurred during processing (before rollback)
            // but database side effects were rolled back
            successfulEvents shouldBe listOf("success:PizzaCreated") // In-memory effects happen during processing

            // FIXED: RelationalDatabaseBookmarkStore.save() now participates in the existing transaction
            // context when called from within EventStore.sink(). This provides true synchronous
            // processing guarantees where all operations rollback together if any processor fails.
            //
            // Both bookmarks should be 0L because the entire transaction was rolled back
            // when the failing processor threw an exception.
            bookmarkStore.bookmarkFor("successful-projector").sequence shouldBe 0L // Fixed: now rolls back properly
            bookmarkStore.bookmarkFor("failing-projector").sequence shouldBe 0L
        }
    }

    describe("BlockingSyncEventProcessor Catchup Validation Integration") {
        it("processes events successfully when validation passes") {
            val processedEvents = mutableListOf<PizzaEvent>()
            val projector = EventProcessor.from<PizzaEvent, StandardEventMetadata> { event, _, _, _, _ ->
                processedEvents.add(event)
            }
            val bookmarkedProjector = BookmarkedEventProcessor.from(bookmarkStore, "validated-projector", projector)

            // Set up validator that will pass (allow gap of 1 since processor starts at 0 and we're creating first event)
            val validator = SyncProcessorCatchupValidator<StandardEventMetadata>(
                eventsSequenceStats,
                CatchupValidationConfig(validationMode = CatchupValidationMode.ENFORCE, allowableGap = 1)
            )

            val syncProcessor = BlockingSyncEventProcessor(
                listOf(bookmarkedProjector),
                catchupValidator = validator
            )

            val eventStore = RelationalDatabaseEventStore.create(
                db,
                eventsSequenceStats = eventsSequenceStats,
                endOfSinkTransactionHook = { sequencedEvents ->
                    syncProcessor.processEvents(sequencedEvents).fold(
                        { error -> throw RuntimeException("Sync processor failed: $error") },
                        { /* success */ }
                    )
                }
            )

            val gateway = EventStoreCommandGateway(
                eventStore,
                Route.from(
                    PizzaAggregate.Companion::create,
                    PizzaAggregate::update,
                    ::PizzaAggregate,
                    PizzaAggregate::updated,
                    PizzaAggregate.Companion::aggregateType,
                )
            )

            // Dispatch command
            val pizzaId = UUID.randomUUID()
            val result = gateway.dispatch(CreateClassicPizza(pizzaId, PizzaStyle.MARGHERITA), metadata)

            // Verify command succeeded
            result shouldBe Right(Created)
            processedEvents.size shouldBe 1
            bookmarkStore.bookmarkFor("validated-projector").sequence shouldBe 1L
        }

        it("fails transaction when catchup validation fails") {
            val processedEvents = mutableListOf<PizzaEvent>()
            val projector = EventProcessor.from<PizzaEvent, StandardEventMetadata> { event, _, _, _, _ ->
                processedEvents.add(event)
            }
            val bookmarkedProjector = BookmarkedEventProcessor.from(bookmarkStore, "behind-projector", projector)

            // First, create an event to establish a baseline sequence
            val initialEventStore = RelationalDatabaseEventStore.create<StandardEventMetadata>(db, eventsSequenceStats = eventsSequenceStats)
            val initialGateway = EventStoreCommandGateway(
                initialEventStore,
                Route.from(
                    PizzaAggregate.Companion::create,
                    PizzaAggregate::update,
                    ::PizzaAggregate,
                    PizzaAggregate::updated,
                    PizzaAggregate.Companion::aggregateType,
                )
            )
            initialGateway.dispatch(CreateClassicPizza(UUID.randomUUID(), PizzaStyle.MARGHERITA), metadata)

            // Set up validator that will fail (processor behind by 1, no gap tolerance)
            val validator = SyncProcessorCatchupValidator<StandardEventMetadata>(
                eventsSequenceStats,
                CatchupValidationConfig(validationMode = CatchupValidationMode.ENFORCE, allowableGap = 0)
            )

            val syncProcessor = BlockingSyncEventProcessor(
                listOf(bookmarkedProjector),
                catchupValidator = validator
            )

            val eventStore = RelationalDatabaseEventStore.create(
                db,
                eventsSequenceStats = eventsSequenceStats,
                endOfSinkTransactionHook = { sequencedEvents ->
                    syncProcessor.processEvents(sequencedEvents).fold(
                        { error -> throw RuntimeException("Sync processor validation failed: $error") },
                        { /* success */ }
                    )
                }
            )

            val gateway = EventStoreCommandGateway(
                eventStore,
                Route.from(
                    PizzaAggregate.Companion::create,
                    PizzaAggregate::update,
                    ::PizzaAggregate,
                    PizzaAggregate::updated,
                    PizzaAggregate.Companion::aggregateType,
                )
            )

            // Attempt to dispatch command - should fail validation
            val pizzaId = UUID.randomUUID()
            val result = runCatching {
                gateway.dispatch(CreateClassicPizza(pizzaId, PizzaStyle.HAWAIIAN), metadata)
            }

            // Verify command failed due to validation
            result.isFailure shouldBe true
            result.exceptionOrNull()?.message?.contains("validation failed") shouldBe true

            // Verify no additional events were persisted due to validation failure
            transaction(db) {
                eventsTable.selectAll().count() shouldBe 1 // Only the initial event
            }

            // Verify projector didn't process new event
            processedEvents.size shouldBe 0
            bookmarkStore.bookmarkFor("behind-projector").sequence shouldBe 0L
        }

        it("processes with WARN validation mode even when behind") {
            val processedEvents = mutableListOf<PizzaEvent>()
            val projector = EventProcessor.from<PizzaEvent, StandardEventMetadata> { event, _, _, _, _ ->
                processedEvents.add(event)
            }
            val bookmarkedProjector = BookmarkedEventProcessor.from(bookmarkStore, "warn-projector", projector)

            // Create baseline events
            val initialEventStore = RelationalDatabaseEventStore.create<StandardEventMetadata>(db, eventsSequenceStats = eventsSequenceStats)
            val initialGateway = EventStoreCommandGateway(
                initialEventStore,
                Route.from(
                    PizzaAggregate.Companion::create,
                    PizzaAggregate::update,
                    ::PizzaAggregate,
                    PizzaAggregate::updated,
                    PizzaAggregate.Companion::aggregateType,
                )
            )
            initialGateway.dispatch(CreateClassicPizza(UUID.randomUUID(), PizzaStyle.MARGHERITA), metadata)

            // Set up validator in WARN mode
            val validator = SyncProcessorCatchupValidator<StandardEventMetadata>(
                eventsSequenceStats,
                CatchupValidationConfig(validationMode = CatchupValidationMode.WARN)
            )

            val syncProcessor = BlockingSyncEventProcessor(
                listOf(bookmarkedProjector),
                catchupValidator = validator
            )

            val eventStore = RelationalDatabaseEventStore.create(
                db,
                eventsSequenceStats = eventsSequenceStats,
                endOfSinkTransactionHook = { sequencedEvents ->
                    syncProcessor.processEvents(sequencedEvents).fold(
                        { error -> throw RuntimeException("Sync processor failed: $error") },
                        { /* success */ }
                    )
                }
            )

            val gateway = EventStoreCommandGateway(
                eventStore,
                Route.from(
                    PizzaAggregate.Companion::create,
                    PizzaAggregate::update,
                    ::PizzaAggregate,
                    PizzaAggregate::updated,
                    PizzaAggregate.Companion::aggregateType,
                )
            )

            // Dispatch command - should succeed with warning
            val pizzaId = UUID.randomUUID()
            val result = gateway.dispatch(CreateClassicPizza(pizzaId, PizzaStyle.HAWAIIAN), metadata)

            // Verify command succeeded despite being behind
            result shouldBe Right(Created)
            processedEvents.size shouldBe 1
            bookmarkStore.bookmarkFor("warn-projector").sequence shouldBe 2L // Processed the new event
        }

        it("uses per-processor validation configs correctly") {
            val processor1Events = mutableListOf<PizzaEvent>()
            val processor2Events = mutableListOf<PizzaEvent>()

            val projector1 = EventProcessor.from<PizzaEvent, StandardEventMetadata> { event, _, _, _, _ ->
                processor1Events.add(event)
            }
            val projector2 = EventProcessor.from<PizzaEvent, StandardEventMetadata> { event, _, _, _, _ ->
                processor2Events.add(event)
            }

            val bookmarkedProjector1 = BookmarkedEventProcessor.from(bookmarkStore, "strict-projector", projector1)
            val bookmarkedProjector2 = BookmarkedEventProcessor.from(bookmarkStore, "lenient-projector", projector2)

            // Create baseline events
            val initialEventStore = RelationalDatabaseEventStore.create<StandardEventMetadata>(db, eventsSequenceStats = eventsSequenceStats)
            val initialGateway = EventStoreCommandGateway(
                initialEventStore,
                Route.from(
                    PizzaAggregate.Companion::create,
                    PizzaAggregate::update,
                    ::PizzaAggregate,
                    PizzaAggregate::updated,
                    PizzaAggregate.Companion::aggregateType,
                )
            )
            initialGateway.dispatch(CreateClassicPizza(UUID.randomUUID(), PizzaStyle.MARGHERITA), metadata)

            // Set projector1 behind by 1 (strict validation should fail)
            // projector2 starts at 0 (lenient validation should pass)

            val validator = SyncProcessorCatchupValidator<StandardEventMetadata>(
                eventsSequenceStats,
                CatchupValidationConfig(validationMode = CatchupValidationMode.ENFORCE)
            )

            val validationConfigs = mapOf(
                "strict-projector" to CatchupValidationConfig(validationMode = CatchupValidationMode.ENFORCE, allowableGap = 0),
                "lenient-projector" to CatchupValidationConfig(validationMode = CatchupValidationMode.WARN)
            )

            val syncProcessor = BlockingSyncEventProcessor(
                listOf(bookmarkedProjector1, bookmarkedProjector2),
                catchupValidator = validator,
                validationConfigs = validationConfigs
            )

            val eventStore = RelationalDatabaseEventStore.create(
                db,
                eventsSequenceStats = eventsSequenceStats,
                endOfSinkTransactionHook = { sequencedEvents ->
                    syncProcessor.processEvents(sequencedEvents).fold(
                        { error -> throw RuntimeException("Sync processor validation failed: $error") },
                        { /* success */ }
                    )
                }
            )

            val gateway = EventStoreCommandGateway(
                eventStore,
                Route.from(
                    PizzaAggregate.Companion::create,
                    PizzaAggregate::update,
                    ::PizzaAggregate,
                    PizzaAggregate::updated,
                    PizzaAggregate.Companion::aggregateType,
                )
            )

            // This should fail because strict-projector is behind by 1 with 0 gap tolerance
            val result = runCatching {
                gateway.dispatch(CreateClassicPizza(UUID.randomUUID(), PizzaStyle.HAWAIIAN), metadata)
            }

            result.isFailure shouldBe true
            processor1Events.size shouldBe 0
            processor2Events.size shouldBe 0
            bookmarkStore.bookmarkFor("strict-projector").sequence shouldBe 0L
            bookmarkStore.bookmarkFor("lenient-projector").sequence shouldBe 0L
        }

        it("bypasses validation when validator is not configured") {
            val processedEvents = mutableListOf<PizzaEvent>()
            val projector = EventProcessor.from<PizzaEvent, StandardEventMetadata> { event, _, _, _, _ ->
                processedEvents.add(event)
            }
            val bookmarkedProjector = BookmarkedEventProcessor.from(bookmarkStore, "no-validation-projector", projector)

            val syncProcessor = BlockingSyncEventProcessor(
                listOf(bookmarkedProjector)
                // No catchupValidator configured
            )

            val eventStore = RelationalDatabaseEventStore.create(
                db,
                eventsSequenceStats = eventsSequenceStats,
                endOfSinkTransactionHook = { sequencedEvents ->
                    syncProcessor.processEvents(sequencedEvents).fold(
                        { error -> throw RuntimeException("Sync processor failed: $error") },
                        { /* success */ }
                    )
                }
            )

            val gateway = EventStoreCommandGateway(
                eventStore,
                Route.from(
                    PizzaAggregate.Companion::create,
                    PizzaAggregate::update,
                    ::PizzaAggregate,
                    PizzaAggregate::updated,
                    PizzaAggregate.Companion::aggregateType,
                )
            )

            // Should succeed regardless of catchup status since no validator
            val pizzaId = UUID.randomUUID()
            val result = gateway.dispatch(CreateClassicPizza(pizzaId, PizzaStyle.MARGHERITA), metadata)

            result shouldBe Right(Created)
            processedEvents.size shouldBe 1
            bookmarkStore.bookmarkFor("no-validation-projector").sequence shouldBe 1L
        }
    }
})