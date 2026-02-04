Kestrel currently supports semi-synchronous projections via BlockingAsyncEventProcessorWaiter. The way it works is that
when a command comes in via the CommandGateway, immediately after the events are sunk using the EventStore, the event store 
hands off to a hook which passes the list of sunk events to an instance of BlockingAsyncEventProcessorWaiter.

This class just waits for any event-processors passed to it to be caught up to the sequence number of the new event(s).
It blocks the user request thread until they're up to date or eventually times out. 

This has provided an ok quick solution to wanting the ability to have synchronous projectors, but it's a little bit slow
and it isn't great relying on a separate process. I'd prefer to have the ability for a list of local synchronous projectors
to handle the events directly in-thread rather than wait for a background thread.

Update the EventStore to accept two hooks, endOfSinkTransactionHook and the existing afterSinkHook. The new hook should
be called within the same transaction, and accepted a list of SequencedEvent.

Implement a BlockingSyncEventProcessorUpdater which takes a list of event processors, and updates them directly. These 
event-processors may or may not be at head depending on if they're projections that have been caught up in the background 
async, and then we're adding them to the sync list for the first time. Add the processing needed to catch them up to the
appropriate sequence number so that they can safely handle these new events. Take inspiration from BlockingAsyncEventProcessorWaiter
just for the part where it filters down based on event-type - not all event-processors need to handle all event types.

Granular plan:

## Phase 1: EventStore Hook Enhancement
- [ ] Update `RelationalDatabaseEventStore` constructor to accept `endOfSinkTransactionHook` parameter
- [ ] Add `endOfSinkTransactionHook` parameter to companion `create()` method with default empty implementation
- [ ] **Test**: Update `RelationalDatabaseEventStoreTest` - verify backward compatibility with existing tests
- [ ] Modify `sink()` method to call new hook WITHIN the database transaction (before commit)
- [ ] **Test**: Verify `endOfSinkTransactionHook` called within transaction and before `afterSinkHook`
- [ ] Add error handling for hook failures - wrap in try-catch to allow transaction rollback
- [ ] **Test**: Verify transaction rollback when `endOfSinkTransactionHook` throws exception

## Phase 2: Event Filtering and Processor Structure
- [ ] Create new `BlockingSyncEventProcessorUpdater` class accepting list of `BookmarkedEventProcessor<M>`
- [ ] Implement event filtering logic using `domainEventClasses()` (pattern from `BlockingAsyncEventProcessorWaiter`)
- [ ] **Test**: Create `BlockingSyncEventProcessorUpdaterTest` - verify event type filtering works correctly
- [ ] **Test**: Test with multiple processors handling different event types

## Phase 3: Catch-up Mechanism
- [ ] **Update `BlockingSyncEventProcessorUpdater`**: Add catch-up mechanism - check bookmark vs new events, fetch missed events with `EventSource.getAfter()`
- [ ] **Test in `BlockingSyncEventProcessorUpdaterTest`**: Test catch-up scenarios when processors are behind current events
- [ ] **Test in `BlockingSyncEventProcessorUpdaterTest`**: Test no-op behavior when processors are already caught up

## Phase 4: Synchronous Processing
- [ ] **Update `BlockingSyncEventProcessorUpdater`**: Implement synchronous event processing - call `processor.process()` for each relevant event
- [ ] **Update `BlockingSyncEventProcessorUpdater`**: Add bookmark updates - save new bookmark after processing each event
- [ ] **Test in `BlockingSyncEventProcessorUpdaterTest`**: Verify bookmark updates after successful processing
- [ ] **Update `BlockingSyncEventProcessorUpdater`**: Handle multiple processors with proper error handling (fail-fast approach)
- [ ] **Test in `BlockingSyncEventProcessorUpdaterTest`**: Test error handling - processor failure causes transaction rollback

## Phase 5: Integration and Final Verification
- [ ] **Test**: Create integration test showing sync and async processors working together
- [ ] **Test**: Test performance impact when no sync processors are configured
- [ ] **Test**: Run full test suite to ensure no regressions: `./gradlew test`