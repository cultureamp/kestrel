package com.cultureamp.common

import kotlin.reflect.KClass

fun <T: Any> KClass<T>.asNestedSealedConcreteClasses(): List<KClass<out T>> {
    return when (this.isFinal) {
        true -> listOf(this)
        false -> this.sealedSubclasses.flatMap { it.asNestedSealedConcreteClasses() }
    }
}