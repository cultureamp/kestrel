package com.cultureamp.script

import com.cultureamp.eventsourcing.Command
import com.cultureamp.eventsourcing.CommandGateway
import com.cultureamp.eventsourcing.CreationCommand
import com.cultureamp.eventsourcing.CreationEvent
import com.cultureamp.eventsourcing.DomainError
import com.cultureamp.eventsourcing.DomainEvent
import com.cultureamp.eventsourcing.Either
import com.cultureamp.eventsourcing.EventMetadata
import com.cultureamp.eventsourcing.Left
import com.cultureamp.eventsourcing.RelationalDatabaseEventStore
import com.cultureamp.eventsourcing.Right
import com.cultureamp.eventsourcing.Route
import com.cultureamp.eventsourcing.SimpleAggregate
import com.cultureamp.eventsourcing.SimpleAggregateConstructor
import com.cultureamp.eventsourcing.UpdateCommand
import com.cultureamp.eventsourcing.UpdateEvent
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.transaction
import java.lang.RuntimeException
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
This script is based on the EventSourcery version[1]. Some of this
documentation is copied from there too.

Demonstrates that sequence IDs may not be inserted linearly with concurrent
writers.

This script writes events in parallel from a number of concurrent threads,
writing events in a continuous loop until the program is interrupted. The
parent process detects gaps in sequence IDs by selecting the last 2 events
based on sequence ID. A gap is detected when the 2 IDs returned from that
query aren't sequential. The script will proceed to execute 2 subsequent
queries to see if they show up in the time it takes to complete those before
moving on.

An easier way to demonstrate this is by using 2 psql consoles:

- Simulate a transaction taking a long time to commit:

begin;
insert into events (..) values (..);

- Then, in another console:

insert into events (..) values (..);
select * from events;

The result is that event sequence ID 2 is visible, but only when the first
transaction commits is event sequence ID 1 visible.

Why does this happen?

Sequences in Postgres (and most other DBs) are not transactional, changes to
the sequence are visible to other transactions immediately. Also, inserts
from the forked writers may be executed in parallel by postgres.

The process of inserting into a table that has a sequence or serial column is
to first get the next sequence ID (changing global state), then perform the
insert statement and later commit. In between these 2 steps the sequence ID
is taken but not visible in the table until the insert statement is
committed. Gaps in sequence IDs occur when a process takes a sequence ID and
commits it while another process is in between those 2 steps.

This means another transaction could have taken the next sequence ID and
committed before that one commits, resulting in a gap in sequence ID's when
reading.

Why is this a problem?

Event stream processors use the sequence ID to keep track of where they're up
to in the events table. If a projector processes an event with sequence ID n,
it assumes that the next event it needs to process will have a sequence ID >
n. This approach isn't reliable when sequence IDs appear non-linearly, making
it possible for event stream processors to skip over events.

How does this framework deal with this?

Use we a transaction level advisory lock to synchronise inserts to the events
table within the Sink class.

Alternatives:

- Write events from 1 process only (serialize at the application level)
- Detect gaps when reading events and allow time for in-flight transactions
(the gaps) to commit.
- Built in eventual consistency. Selects would be restricted to events older
than 500ms-1s or the transaction timeout to give enough time for in-flight
transactions to commit.
- Only query events when catching up, after that rely on events to be
delivered through the pub/sub mechanism. Given events would be received out
of order under concurrent writes there's potential for processors to
process a given event twice if they shutdown after processing a sequence
that was part of a gap.

[1]: https://github.com/envato/event_sourcery-postgres/blob/9fa5cec446e9335edb5b8d4aa2517d383c73b076/script/demonstrate_event_sequence_id_gaps.rb
 */
fun main(args: Array<String>) {
    val jdbcUrl = args.getOrElse(0) { "jdbc:postgresql://localhost:5432/demonstrate_sequence_gaps" }
    val driver = args.getOrElse(1) { "org.postgresql.Driver" }
    val user = args.getOrElse(2) { "william.boxhall" }
    val database = Database.connect(url = jdbcUrl, driver = driver, user = user)
    val eventStore = RelationalDatabaseEventStore.create<EventMetadata>(database)
    eventStore.createSchemaIfNotExists()

    transaction(database) {
        exec("TRUNCATE events RESTART IDENTITY;")
    }

    val commandGateway = CommandGateway(
        eventStore,
        Route.from(SimpleThingAggregate)
    )

    val stop = AtomicBoolean(false)
    val mainThread = Thread.currentThread()
    Runtime.getRuntime().addShutdownHook(object : Thread() {
        override fun run() {
            try {
                println("Trapped sigterm")
                stop.set(true)
                mainThread.join()
            } catch (ex: InterruptedException) {
                println(ex)
            }
        }
    })

    val startTime = System.currentTimeMillis()
    val threads = (1..20).map {
        thread(start = true, isDaemon = false, name = it.toString()) {
            while (!stop.get()) {
                val result = commandGateway.dispatch(CreateSimpleThing(UUID.randomUUID()), EventMetadata())
                when (result) {
                    is Left -> throw RuntimeException(result.error.toString())
                    else -> {}
                }
            }
        }
    }

    // This represents an event processor:
    var bookmark = 0L
    val processedSequences = mutableListOf<Long>()
    while (true) {
        val events = eventStore.getAfter(bookmark)

        events.forEach {
            processedSequences.add(it.sequence)
            bookmark = it.sequence
            if ((bookmark % 100) == 0L) {
                println("Processed to $bookmark")
            }
        }

        if (stop.get()) {
            println("Waiting for remaining events...")
            threads.forEach {
                it.join()
            }
            if (eventStore.getAfter(bookmark).isEmpty()) {
                break
            }
        }
    }

    val actualSequences = transaction(database) {
        eventStore.getAfter(sequence = 0, batchSize = Integer.MAX_VALUE).map { it.sequence }
    }

    println("actual sequences:\t${actualSequences.count()}")
    println("processed sequences:\t${processedSequences.count()}")
    println("unprocessed sequences:\t${(actualSequences - processedSequences).count()}")
    println("Done")
    val endTime = System.currentTimeMillis()
    val elapsed = endTime - startTime
    println("Elapsed ms: $elapsed")
}

sealed class SimpleThingCommand : Command

data class CreateSimpleThing(override val aggregateId: UUID) : SimpleThingCommand(), CreationCommand

sealed class SimpleThingUpdateCommand : SimpleThingCommand(), UpdateCommand
data class Twerk(override val aggregateId: UUID, val tweak: String) : SimpleThingUpdateCommand()

sealed class SimpleThingEvent : DomainEvent

object SimpleThingCreated : SimpleThingEvent(), CreationEvent

sealed class SimpleThingUpdateEvent : SimpleThingEvent(), UpdateEvent
data class Twerked(val tweak: String) : SimpleThingUpdateEvent()

sealed class SimpleThingError : DomainError

data class SimpleThingAggregate(val tweaks: List<String> = emptyList()) : SimpleAggregate<SimpleThingUpdateCommand, SimpleThingUpdateEvent> {
    companion object : SimpleAggregateConstructor<CreateSimpleThing, SimpleThingCreated, SimpleThingUpdateCommand, SimpleThingUpdateEvent> {
        override fun created(event: SimpleThingCreated) = SimpleThingAggregate()

        override fun create(command: CreateSimpleThing): Either<DomainError, SimpleThingCreated> = Right(SimpleThingCreated)
    }

    override fun updated(event: SimpleThingUpdateEvent) = when (event) {
        is Twerked -> this.copy(tweaks = tweaks + event.tweak)
    }

    override fun update(command: SimpleThingUpdateCommand) = when (command) {
        is Twerk -> Right.list(Twerked(command.tweak))
    }
}
