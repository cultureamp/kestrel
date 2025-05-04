# 0.22.0

## Breaking changes

This release moves makes the updating of `EventsSequenceStats` during `RelationalDatabaseEventStore#sink` optional, 
so that applications can choose to not to this synchronously and update these 
stats asynchronously.

This moves the `#lastSequence(...)` method out of the `EventSource` interface and onto a new
`EventsSequenceStats` class. To migrate, just switch over to calling the method
in its new location.

# 0.25.0

## Breaking changes

The interface for `EventTypeResolver` has changed to support the event-store filtering by `aggregate-type` 
along side `event-type`. This will only affect codebases that provided a custom implementation of this interface.
See `PackageRemovingEventTypeResolver` for an example of how to work with the new interface.