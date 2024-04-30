package com.cultureamp.eventsourcing

import com.cultureamp.eventsourcing.example.Created
import com.cultureamp.eventsourcing.example.Deleted
import com.cultureamp.eventsourcing.example.Renamed
import com.cultureamp.eventsourcing.example.Restored
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import org.jetbrains.exposed.sql.Database
import org.testcontainers.containers.PostgreSQLContainer
import kotlin.reflect.KClass

class EventsSequenceStatsTest : DescribeSpec({
    // using postgres because h2 has a bug in the "upsert" method
    val postgres = PostgreSQLContainer("postgres:14.3")
    postgres.start()
    val db = Database.connect(
        url = postgres.getJdbcUrl(),
        user = postgres.getUsername(),
        password = postgres.getPassword(),
    )

    val eventsSequenceStats = RelationalDatabaseEventsSequenceStats(db)
    eventsSequenceStats.createSchemaIfNotExists()

    fun updateSequence(eventClass: KClass<out DomainEvent>, sequence: Long) {
        eventsSequenceStats.save(eventClass, sequence)

        // Idempotency
        eventsSequenceStats.save(eventClass, sequence)
    }

    describe("RelationalDatabaseEventsSequenceStats") {
        it("exposes max sequence given event types") {
            updateSequence(Created::class, 1)
            updateSequence(Renamed::class, 2)
            updateSequence(Deleted::class, 3)

            eventsSequenceStats.lastSequence(listOf(Created::class)) shouldBe 1
            eventsSequenceStats.lastSequence(listOf(Renamed::class)) shouldBe 2
            eventsSequenceStats.lastSequence(listOf(Deleted::class)) shouldBe 3
            eventsSequenceStats.lastSequence(listOf(Created::class, Deleted::class)) shouldBe 3
            eventsSequenceStats.lastSequence(listOf()) shouldBe 3
            eventsSequenceStats.lastSequence(listOf(Restored::class)) shouldBe 0
        }
    }
})
