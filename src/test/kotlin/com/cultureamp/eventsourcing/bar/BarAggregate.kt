package com.cultureamp.developmodule.eventsourcing.bar

import com.cultureamp.eventsourcing.DomainEvent

interface BarAggregate
sealed interface BarEvent : DomainEvent
data class FirstBarEvent(val value: String) : BarEvent
data class SameNameEvent(val value: String) : BarEvent
