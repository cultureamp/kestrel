# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Kestrel is a Kotlin framework for building event-sourced, CQRS applications. It implements event sourcing where application state is stored as an immutable sequence of domain events, combined with Command/Query Responsibility Segregation (CQRS).

## Essential Commands

### Building and Testing
```bash
# Run all tests
./gradlew test

# Run specific test class
./gradlew test --tests ClassName

# Run specific test method
./gradlew test --tests "ClassName.test name"

# Build project (compile + test)
./gradlew build

# Clean build
./gradlew clean build
```

### Publishing
```bash
# Local development (no GPG signing required)
SKIP_SIGNING=true ./gradlew publishToMavenLocal

# Production release (requires GPG key and Sonatype credentials)
export SONATYPE_USERNAME=<username>
export SONATYPE_PASSWORD=<password>
./gradlew clean build publish
```

### Security and CI
```bash
# OWASP dependency vulnerability scan
./gradlew dependencyCheckAnalyze

# CI build script
bin/ci_build
```

## Core Architecture

### Event Sourcing + CQRS Pattern
- **Aggregates** contain business logic and handle commands to produce events
- **Commands** represent requests to change system state (Creation/Update)
- **Events** are immutable facts stored in sequence, forming the source of truth
- **EventStore** persists and retrieves events (PostgreSQL/H2 implementation)
- **CommandGateway** routes commands to aggregates, orchestrates loading/saving
- **EventProcessors** build projections (Projector) or trigger side effects (Reactor)

### Key Domain Concepts

**Aggregate Interfaces** (from simplest to most flexible):
- `SimpleAggregate[Constructor]` - Basic interface, recommended starting point
- `SimpleAggregate[Constructor]WithProjection` - With dependency injection
- `Aggregate[Constructor]` - Explicit error and self types
- `Aggregate[Constructor]WithProjection` - With dependencies and explicit types
- Function-based approach - Maximum flexibility using functions instead of interfaces

**Error Handling with Either Monad**:
- `Left(error)` - failure case, no exceptions for domain errors
- `Right(value)` - success case
- `RetriableError` interface for automatic retries

**Event Processing Patterns**:
- **AsyncEventProcessor** reads events, processes them, maintains bookmark for progress
- **BookmarkStore** tracks last processed sequence number per processor
- **BatchedAsyncEventProcessor** for efficient async processing with backpressure

## Database Schema

### Events Table (Primary)
```sql
events (
  sequence BIGINT AUTO_INCREMENT PRIMARY KEY,  -- Global ordering
  id UUID,                                    -- Event ID
  aggregate_sequence BIGINT,                  -- Per-aggregate ordering
  aggregate_id UUID,                          -- Aggregate identifier
  aggregate_type VARCHAR(128),                -- Aggregate type
  event_type VARCHAR(256),                    -- Event type for filtering
  created_at DATETIME,                        -- Creation timestamp
  json_body JSONB,                           -- Event data as JSON
  metadata JSONB,                            -- Event metadata
  UNIQUE(aggregate_id, aggregate_sequence)   -- Optimistic concurrency
)
```

### Supporting Tables
- `bookmarks` - Event processor progress tracking
- `events_sequence_stats` - Last sequence per event type (optimization)

## Technology Stack

### Core Technologies
- **Kotlin 1.9.23** with Java 17 target
- **PostgreSQL** (production) / **H2** (testing) for event storage
- **Jetbrains Exposed 0.49.0** - Kotlin SQL DSL for database access
- **Jackson 2.18.1** - JSON serialization with snake_case naming
- **Joda-Time** - Date/time handling (not java.time)
- **Kotest 4.6.2** - Testing framework with assertions
- **Testcontainers** - PostgreSQL integration testing

### Important Dependencies
```kotlin
// Database
exposed-core, exposed-jdbc, exposed-jodatime, exposed-json
postgresql:42.7.3

// Serialization
jackson-module-kotlin, jackson-datatype-joda

// Testing
kotest-runner-junit5-jvm, testcontainers:postgresql
```

## Development Patterns

### Adding New Aggregates
1. Define sealed command classes (Creation/Update extending appropriate base)
2. Define sealed event classes (Creation/Update extending appropriate base)
3. Define error sealed classes extending DomainError
4. Implement aggregate using chosen pattern (interface or function-based)
5. Register with CommandGateway via Route configuration

### Event Processor Setup
```kotlin
// Create processor
val processor = EventProcessor.from { event -> /* handle event */ }

// Wrap for async processing
val asyncProcessor = BatchedAsyncEventProcessor(
    eventSource = eventStore,
    eventProcessor = processor,
    bookmarkStore = bookmarkStore,
    name = "processor-name"
)

// Start with retry logic
asyncProcessor.start(ExponentialBackoff())
```

### Concurrency and Reliability
- **Optimistic Concurrency**: Uses aggregate_sequence for conflict detection
- **Advisory Locks**: PostgreSQL session locks coordinate bookmark updates
- **Retry Strategy**: Automatic retries (up to 5) for RetriableError types
- **Idempotency**: Processors designed for at-least-once delivery

## Configuration Management

### Version Management
- Update `base_version` in `gradle.properties` for releases
- Follow semantic versioning
- Update CHANGELOG.md for breaking changes

### Environment Variables
- `SONATYPE_USERNAME` / `SONATYPE_PASSWORD` - Maven Central publishing
- `SKIP_SIGNING=true` - Bypass GPG signing for local development

## Testing Strategy

### Test Organization
- Unit and integration tests use both H2 and PostgreSQL
- Example aggregates in `src/test/.../example/` (Survey, Pizza, Thing, etc.)
- Testcontainers for PostgreSQL integration tests

### Running Specific Tests
```bash
# Integration test example
./gradlew test --tests CommandGatewayIntegrationTest

# With verbose output
./gradlew test --info
```

## Key Architectural Insights

### Design Philosophy
- **Immutable Events**: Once persisted, events never change
- **Type Safety**: Heavy use of Kotlin generics for compile-time correctness
- **Functional Error Handling**: Either monad instead of exceptions
- **Event Upcasting**: Support schema evolution via `@UpcastEvent` annotation

### Concurrency Model
- Single events table with global sequence provides total ordering
- Unique constraint on (aggregate_id, aggregate_sequence) prevents conflicts
- Advisory locks ensure bookmark coordination across processor instances
- 500ms delay between automatic retries

### Current Development Context
- Main branch: `master`
- Current branch: `wb/sequence_number_for_event_processors`
- Recent work focuses on table lock timeout configuration and event type filtering