package com.cultureamp.eventsourcing

import com.cultureamp.developmodule.eventsourcing.bar.BarAggregate
import com.cultureamp.developmodule.eventsourcing.bar.BarEvent
import com.cultureamp.developmodule.eventsourcing.baz.BazAggregate
import com.cultureamp.developmodule.eventsourcing.baz.BazEvent
import com.cultureamp.developmodule.eventsourcing.baz.FirstBazEvent
import com.cultureamp.developmodule.eventsourcing.baz.SecondBazEvent
import com.cultureamp.developmodule.eventsourcing.foo.FirstFooEvent
import com.cultureamp.developmodule.eventsourcing.foo.FooAggregate
import com.cultureamp.developmodule.eventsourcing.foo.FooEvent
import com.cultureamp.developmodule.eventsourcing.foo.SameNameEvent
import io.kotest.core.spec.style.DescribeSpec
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Assertions.assertEquals

internal class PackageRemovingEventTypeResolverTest : DescribeSpec({
    describe("RelationalDatabaseEventsSequenceStats") {
        it("fails with meaningful error when same event name exists in multiple aggregates") {
            val error = Assertions.assertThrows(IllegalArgumentException::class.java) {
                PackageRemovingEventTypeResolver(mapOf(FooAggregate::class to FooEvent::class, BarAggregate::class to BarEvent::class))
            }
            assertEquals("Event names [SameNameEvent] exist in more than one aggregate", error.message)
        }

        it("allows event types to be persisted without full package names") {
            val eventTypeResolver = PackageRemovingEventTypeResolver(mapOf(FooAggregate::class to FooEvent::class, BazAggregate::class to BazEvent::class))

            assertEquals(EventTypeDescription("FirstFooEvent", "FooAggregate"), eventTypeResolver.serialize(FirstFooEvent::class.java))
            assertEquals(EventTypeDescription("SameNameEvent", "FooAggregate"), eventTypeResolver.serialize(SameNameEvent::class.java))
            assertEquals(EventTypeDescription("FirstBazEvent", "BazAggregate"), eventTypeResolver.serialize(FirstBazEvent::class.java))
            assertEquals(EventTypeDescription("SecondBazEvent", "BazAggregate"), eventTypeResolver.serialize(SecondBazEvent::class.java))
            assertEquals(FirstFooEvent::class.java, eventTypeResolver.deserialize(EventTypeDescription("FirstFooEvent", "FooAggregate")))
            assertEquals(SameNameEvent::class.java, eventTypeResolver.deserialize(EventTypeDescription("SameNameEvent", "FooAggregate")))
            assertEquals(FirstBazEvent::class.java, eventTypeResolver.deserialize(EventTypeDescription("FirstBazEvent", "BazAggregate")))
            assertEquals(SecondBazEvent::class.java, eventTypeResolver.deserialize(EventTypeDescription("SecondBazEvent", "BazAggregate")))

            Assertions.assertThrows(NoSuchElementException::class.java) {
                eventTypeResolver.deserialize(EventTypeDescription("ThirdFooEvent", "FooAggregate"))
            }
        }
    }
})
