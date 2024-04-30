# 0.22.0

## Breaking changes

This release moves makes the updating of `EventsSequenceStats` during `RelationalDatabaseEventStore#sink` optional, 
so that applications can choose to not to this synchronously and update these 
stats asynchronously.

This moves the `#lastSequence(...)` method out of the `EventSource` interface and onto a new
`EventsSequenceStats` class. To migrate, just switch over to calling the method
in its new location.
