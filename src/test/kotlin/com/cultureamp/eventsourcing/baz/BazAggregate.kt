package com.cultureamp.developmodule.eventsourcing.baz

import com.cultureamp.eventsourcing.DomainEvent

interface BazAggregate
sealed interface BazEvent : DomainEvent
data class FirstBazEvent(val value: String) : BazEvent
data class SecondBazEvent(val value: String) : BazEvent
