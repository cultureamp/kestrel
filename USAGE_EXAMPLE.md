# EventProcessor with Sequence Support - Usage Example

## Simple and Clean API

Now you can easily access sequence numbers in your event processors:

```kotlin
// Function-based approach - exactly what you wanted!
val processor = EventProcessor.from { event: MyEvent, aggregateId: UUID, metadata: MyMetadata, eventId: UUID, sequence: Long ->
    println("Processing event $event at sequence $sequence")
}

// Or interface-based approach
class MySequenceProcessor : DomainEventProcessorWithSequence<MyEvent, MyMetadata> {
    override fun process(event: MyEvent, aggregateId: UUID, metadata: MyMetadata, eventId: UUID, sequence: Long) {
        println("Processing event $event at sequence $sequence")
    }
}
```

## Key Changes Made

1. **Simplified EventProcessor**: Now has `fun process(event: Event<M>, sequence: Long)`
2. **Removed SequencedEventProcessor**: No longer needed as a separate interface
3. **All processors work the same way**: Every processor receives sequence number
4. **Backward compatibility maintained**: Existing code still works

## Clean Architecture

- `BatchedAsyncEventProcessor` directly calls `eventProcessor.process(event.event, event.sequence)`
- No wrapper complexity or type-checking overhead
- Single, consistent API for all event processing

This achieves exactly what you wanted - a clean, simple way to access sequence numbers without the complexity of separate interfaces!