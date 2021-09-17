package com.cultureamp.common

fun String.toSnakeCase(): String {
    var text = ""
    var isFirst = true
    this.forEach {
        if (it.isUpperCase()) {
            if (isFirst) isFirst = false
            else text += "_"
            text += it.lowercaseChar()
        } else {
            text += it
        }
    }
    return text
}