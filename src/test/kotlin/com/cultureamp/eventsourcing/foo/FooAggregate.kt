package com.cultureamp.developmodule.eventsourcing.foo

import com.cultureamp.eventsourcing.DomainEvent

interface FooAggregate
sealed interface FooEvent : DomainEvent
data class FirstFooEvent(val value: String) : FooEvent
data class SameNameEvent(val value: String) : FooEvent
