# Synchronous Projector Architecture Plan for Kestrel

## Overview

This plan outlines the implementation of truly synchronous projectors in the Kestrel event-sourcing framework. The current implementation only supports pseudo-synchronous behavior via `BlockingAsyncEventProcessorWaiter`, which polls async processor bookmarks. The new system will execute projectors within the same database transaction as command processing, providing true synchronous consistency.

## Current Architecture Analysis

### Command Processing Flow
1. `CommandGateway.dispatch()` → Retry wrapper (5x, 500ms backoff)
2. `AggregateConstructor.create/update()` → Load aggregate, execute business logic
3. `EventStore.sink()` → **Single database transaction** wraps all persistence
4. Async processors poll events via `BatchedAsyncEventProcessor` **outside transaction**
5. `BlockingAsyncEventProcessorWaiter` provides pseudo-sync by polling bookmarks

### Key Components
- **RelationalDatabaseEventStore**: Manages transactions, PostgreSQL advisory locks
- **BookmarkStore**: Tracks processor progress with advisory locking
- **EventProcessor interface**: Flexible abstraction for event handlers
- **AfterSinkHook**: Extension point triggered after event persistence

## Requirements

### Core Requirements
1. **True synchronous execution**: Projectors run within the same database transaction as command processing
2. **Bookmark consistency**: Synchronous projectors have immediately consistent bookmarks
3. **Error propagation**: Projector failures cause entire command to fail and rollback
4. **A/B substitution safety**: Build "_b" projection async, atomically swap with "_a"

### Technical Constraints
- Must integrate with existing `transaction(db)` boundary in `EventStore.sink()`
- Preserve existing async processor behavior and BookmarkStore interface
- Support mixed sync/async projector configurations per deployment
- Handle async processor lag during A/B swaps safely

## Proposed Architecture

### 1. Enhanced EventStore Hook System

**Extend existing EventStore constructor to support end-of-transaction hooks**
```kotlin
class RelationalDatabaseEventStore<M : EventMetadata>(
    // ... existing parameters
    private val endOfSinkTransactionHook: (List<SequencedEvent<M>>) -> Unit = {},
    private val afterSinkTransactionHook: (List<SequencedEvent<M>>) -> Unit = {}
) {
    // endOfSinkTransactionHook executes BEFORE transaction commit (sync processors)
    // afterSinkTransactionHook executes AFTER transaction commit (async processors)
    // Both receive SequencedEvent<M> with sequence numbers from database
}
```

**BlockingSyncEventProcessor - Direct synchronous processor**
```kotlin
class BlockingSyncEventProcessor<M : EventMetadata>(
    private val eventProcessors: List<BookmarkedEventProcessor<M>>,
    private val timeoutMs: Long = 5000
) {
    fun processEvents(sequencedEvents: List<SequencedEvent<M>>): Either<SyncProcessorError, Unit> {
        // Process sequenced events directly through each processor
        // Update bookmarks within same transaction using sequence numbers
        // Support projector catch-up using sequence numbers for A/B substitution
        // Fail fast if any processor fails or times out
    }
}
```

**BookmarkedEventProcessor abstraction (existing or enhanced)**
```kotlin
// Use existing abstraction that includes both EventProcessor and BookmarkStore
// This is what both async and sync processors can work with
data class BookmarkedEventProcessor<M : EventMetadata>(
    val name: String,
    val eventProcessor: EventProcessor<M>,
    val bookmarkStore: BookmarkStore
)
```

### 2. Integration Points

**EventStore Usage Pattern**
```kotlin
// Create sync processor
val syncProcessor = BlockingSyncEventProcessor(
    eventProcessors = listOf(
        BookmarkedEventProcessor("projector1", projector1, bookmarkStore),
        BookmarkedEventProcessor("projector2", projector2, bookmarkStore)
    )
)

// Create EventStore with end-of-transaction hook
val eventStore = RelationalDatabaseEventStore(
    // ... existing parameters
    endOfSinkTransactionHook = { sequencedEvents ->
        syncProcessor.processEvents(sequencedEvents).fold(
            { error -> throw SyncProcessorException(error) },
            { /* success - continue */ }
        )
    },
    afterSinkTransactionHook = { sequencedEvents ->
        // Existing async processor notifications
        asyncProcessorNotifier.notify(sequencedEvents)
    }
)
```

**Enhanced sink() implementation**
```kotlin
// In RelationalDatabaseEventStore.sink():
transaction(db) {
    // 1. Insert events
    val sequencedEvents = insertEvents(events)

    // 2. Execute end-of-transaction hook (NEW - sync processors)
    endOfSinkTransactionHook(sequencedEvents) // This can throw and cause transaction rollback

    // 3. Update EventsSequenceStats
    updateSequenceStats(sequencedEvents)

    // 4. Commit transaction
}
// 5. Execute after-sink-transaction hooks (existing - async processors)
afterSinkTransactionHook(sequencedEvents)
```

### 3. Bookmark Management Strategy

**Synchronous Bookmarks**
- Updated within same transaction as event storage
- No advisory locking needed (single-threaded per transaction)
- Always consistent with event persistence

**Async Bookmark Coordination**
- Continue using existing advisory lock mechanism
- Can lag behind sync bookmarks safely
- Naming convention: "sync_processor_name" vs "async_processor_name"

### 4. A/B Projection Substitution - Simplified Approach

**The Challenge**: Build projection_b asynchronously, then safely substitute it for projection_a in sync processing.

**Simple Substitution Strategy**
```kotlin
// Phase 1: Build projection_b async while projection_a serves sync traffic
val projectionASync = BookmarkedEventProcessor("projection_a", projectorA, bookmarkStore)
val projectionBAsync = BatchedAsyncEventProcessor("projection_b_builder", projectorB, ...)

val currentSyncProcessor = BlockingSyncEventProcessor(listOf(projectionASync))

// Phase 2: Once projection_b catches up, create new sync processor configuration
val projectionBBookmark = bookmarkStore.bookmarkFor("projection_b_builder")
val projectionABookmark = bookmarkStore.bookmarkFor("projection_a")

// Ensure projection_b is caught up
require(projectionBBookmark.sequence >= projectionABookmark.sequence) {
    "projection_b not caught up: ${projectionBBookmark.sequence} < ${projectionABookmark.sequence}"
}

// Phase 3: Deploy with new sync processor configuration
val projectionBSync = BookmarkedEventProcessor("projection_b", projectorB, bookmarkStore)
val newSyncProcessor = BlockingSyncEventProcessor(listOf(projectionBSync))

// Update EventStore configuration (deployment change)
val eventStore = RelationalDatabaseEventStore(
    // ... existing parameters
    endOfSinkTransactionHook = { sequencedEvents -> newSyncProcessor.processEvents(sequencedEvents) }
)
```

**Key Benefits**:
- No complex promotion logic during command processing
- Clean separation: async build → validation → deployment switch
- Uses existing BookmarkedEventProcessor abstraction for both sync and async
- Failed switches are just deployment rollbacks

### 5. Error Handling & Performance

**Error Propagation**
- Sync projector failures cause immediate command failure
- Full transaction rollback preserves consistency
- Retry mechanism at CommandGateway level (existing 5x retry)

**Critical Performance Constraints**
- EventStore uses `pg_advisory_xact_lock(-1)` - **table-level transaction lock** during entire sink operation
- This is a significant bottleneck - transactions must complete as fast as possible
- Sync projectors add latency to command processing while holding this table lock

**Error Handling Strategy**
- **Fail entire command**: If sync projector fails, entire command fails and transaction rolls back
- **CommandGateway retry**: Leverage existing 5x retry mechanism with 500ms backoff
- **No partial success**: Either all sync projectors succeed or command fails completely
- **Monitoring**: Track sync projector failure rates and impact on command success rate

**Performance Mitigations**
- **Aggressive timeouts**: Very short timeouts per sync projector (100-500ms)
- **Minimal work principle**: Sync projectors should do minimal work (simple updates/inserts only)
- **Parallel execution**: Execute independent sync projectors concurrently to minimize total time
- **Monitoring**: Track sync projector execution time impact on command latency
- **Catch-up optimization**: Cache recently promoted projectors to avoid repeated catch-up work

## Implementation Plan

### Phase 1: Enhanced EventStore Hook System ✅ COMPLETED
1. ✅ Add `endOfSinkTransactionHook` parameter to `RelationalDatabaseEventStore` constructor
2. ✅ Modify `sink()` method to execute end-of-transaction hook before commit
3. ✅ Ensure hooks can throw exceptions to cause transaction rollback
4. ✅ Preserve existing `afterSinkTransactionHook` behavior for async processors
5. ✅ Add unit tests for hook execution order and error propagation

### Phase 2: BlockingSyncEventProcessor Implementation
1. Create `BlockingSyncEventProcessor` class modeled after `BlockingAsyncEventProcessorWaiter`
2. Implement direct event processing (no polling, no waiting)
3. Add timeout and parallel execution support for multiple processors
4. Integrate with existing `BookmarkedEventProcessor` abstraction
5. Add comprehensive error handling and bookmark management

### Phase 3: Integration and Testing
1. Create integration tests with sync processors in EventStore hooks
2. Test performance impact on command processing with table-level lock
3. Validate bookmark consistency between sync and async processors
4. Test error scenarios and transaction rollback behavior
5. Add monitoring for sync processor execution time

### Phase 4: A/B Substitution Tooling
1. Build utilities for validating projection catch-up status
2. Create deployment helpers for switching sync processor configurations
3. Add operational tooling for managing projection lifecycle
4. Build automated validation for safe projection swaps
5. Documentation and migration guides

## Critical Files for Implementation

- `RelationalDatabaseEventStore.kt:84-126` - Core transaction integration
- `EventProcessor.kt` - New SynchronousEventProcessor interface
- `BookmarkStore.kt` - Sync bookmark management extensions
- `CommandGateway.kt` - Error handling extensions
- `BatchedAsyncEventProcessor.kt` - Pattern reference for SynchronousProjectorManager

## Verification Strategy

### End-to-End Testing
1. **Command Processing**: Verify sync projectors execute within command transaction
2. **Bookmark Consistency**: Verify sync projector bookmarks update atomically with events
3. **Error Handling**: Verify projector failures cause command failure and rollback
4. **A/B Substitution**: Verify safe atomic swapping with async lag scenarios
5. **Performance**: Benchmark sync vs async projector latency impact
6. **Mixed Scenarios**: Test combinations of sync/async projectors

### Integration Testing
- Use existing Testcontainers PostgreSQL setup
- Test with example aggregates (Survey, Pizza, Thing)
- Verify bookmark store behavior across sync/async processors
- Test advisory lock coordination between async processor instances

This plan provides a comprehensive approach to implementing truly synchronous projectors while preserving the existing async architecture and enabling safe A/B testing scenarios.