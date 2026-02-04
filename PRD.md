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
<individual tasks defined by claude and ticked off one by one in a markdown list>