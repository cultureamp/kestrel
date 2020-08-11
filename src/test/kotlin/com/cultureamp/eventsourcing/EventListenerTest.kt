package com.cultureamp.eventsourcing

import arrow.core.Tuple3
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.maps.shouldContain
import org.joda.time.DateTime
import java.util.*

class EventListenerTest : DescribeSpec({
    val fooDomainEvent = FooEvent("bar")
    val fooEvent = Event(
        id = UUID.randomUUID(),
        aggregateId = UUID.randomUUID(),
        aggregateSequence = 1,
        createdAt = DateTime.now(),
        metadata = SpecificMetadata("specialField"),
        domainEvent = fooDomainEvent
    )
    val bazDomainEvent = FooEvent("quux")
    val bazEvent = Event(
        id = UUID.randomUUID(),
        aggregateId = UUID.randomUUID(),
        aggregateSequence = 1,
        createdAt = DateTime.now(),
        metadata = SpecificMetadata("specialField"),
        domainEvent = bazDomainEvent
    )

    describe("EventListener#from((E, UUID) -> Any?)") {
        it("can handle events with their aggregateIds") {
            val events = mutableMapOf<UUID, TestEvent>()

            class Projector {
                fun project(event: FooEvent, aggregateId: UUID) {
                    events[aggregateId] = event
                }
            }
            val eventListener = EventListener.from(Projector()::project)

            eventListener.handle(fooEvent)

            events shouldContain (fooEvent.aggregateId to fooDomainEvent)
        }
    }

    describe("EventListener#from((DomainEvent, UUID, EventMetadata, UUID) -> Any?)") {
        it("can handle events with their aggregateIds, metadata and eventIds") {
            val events = mutableMapOf<UUID, Tuple3<TestEvent, EventMetadata, UUID>>()
            class ProjectorWithMetadata {
                fun project(event: FooEvent, aggregateId: UUID, metadata: SpecificMetadata, eventId: UUID) {
                    events[aggregateId] = Tuple3(event, metadata, eventId)
                }
            }

            val eventListener = EventListener.from(ProjectorWithMetadata()::project)

            eventListener.handle(fooEvent)

            events shouldContain (fooEvent.aggregateId to Tuple3(fooDomainEvent, fooEvent.metadata, fooEvent.id))
        }
    }

    describe("EventListener#compose") {
        it("can combine two EventListeners into one") {
            val events = mutableMapOf<UUID, TestEvent>()
            class Projector {
                fun project(event: FooEvent, aggregateId: UUID) {
                    events[aggregateId] = event
                }
            }
            class ProjectorWithMetadata {
                fun project(event: BazEvent, aggregateId: UUID, metadata: SpecificMetadata, eventId: UUID) {
                    events[aggregateId] = event
                }
            }

            val eventListener = EventListener.compose(
                EventListener.from(Projector()::project),
                EventListener.from(ProjectorWithMetadata()::project)
            )

            eventListener.handle(fooEvent)
            eventListener.handle(bazEvent)

            events shouldContain (fooEvent.aggregateId to fooDomainEvent)
            events shouldContain (bazEvent.aggregateId to bazDomainEvent)
        }
    }

})

data class SpecificMetadata(val specialField: String) : EventMetadata()

sealed class TestEvent : DomainEvent
data class FooEvent(val bar: String) : TestEvent()
data class BazEvent(val quux: String) : TestEvent()
