package com.cultureamp.eventsourcing

import com.cultureamp.developmodule.eventsourcing.bar.BarEvent
import com.cultureamp.developmodule.eventsourcing.baz.BazEvent
import com.cultureamp.developmodule.eventsourcing.baz.FirstBazEvent
import com.cultureamp.developmodule.eventsourcing.baz.SecondBazEvent
import com.cultureamp.developmodule.eventsourcing.foo.FirstFooEvent
import com.cultureamp.developmodule.eventsourcing.foo.FooEvent
import com.cultureamp.developmodule.eventsourcing.foo.SameNameEvent
import io.kotest.core.spec.style.DescribeSpec
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Assertions.assertEquals

internal class PackageRemovingEventTypeResolverTest : DescribeSpec({
    describe("RelationalDatabaseEventsSequenceStats") {
        it("fails with meaningful error when same event name exists in multiple aggregates") {
            val error = Assertions.assertThrows(IllegalArgumentException::class.java) {
                PackageRemovingEventTypeResolver(FooEvent::class, BarEvent::class)
            }
            assertEquals("Event names [SameNameEvent] exist in more than one aggregate", error.message)
        }

        it("allows event types to be persisted without full package names") {
            val eventTypeResolver = PackageRemovingEventTypeResolver(FooEvent::class, BazEvent::class)

            assertEquals("FirstFooEvent", eventTypeResolver.serialize(FirstFooEvent::class.java))
            assertEquals("SameNameEvent", eventTypeResolver.serialize(SameNameEvent::class.java))
            assertEquals("FirstBazEvent", eventTypeResolver.serialize(FirstBazEvent::class.java))
            assertEquals("SecondBazEvent", eventTypeResolver.serialize(SecondBazEvent::class.java))
            assertEquals(FirstFooEvent::class.java, eventTypeResolver.deserialize("unused", "FirstFooEvent"))
            assertEquals(SameNameEvent::class.java, eventTypeResolver.deserialize("unused", "SameNameEvent"))
            assertEquals(FirstBazEvent::class.java, eventTypeResolver.deserialize("unused", "FirstBazEvent"))
            assertEquals(SecondBazEvent::class.java, eventTypeResolver.deserialize("unused", "SecondBazEvent"))

            Assertions.assertThrows(NoSuchElementException::class.java) {
                eventTypeResolver.deserialize("", "ThirdFooEvent")
            }
        }
    }
})
