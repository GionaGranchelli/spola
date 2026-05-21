package dev.spola.util

import kotlinx.serialization.json.*

/**
 * Convert a kotlinx.serialization JsonElement to a plain Kotlin value.
 */
fun jsonElementToUntypedValue(element: JsonElement): Any? = when (element) {
    is JsonPrimitive -> when {
        element.isString -> element.content
        element.long != null -> {
            val l = element.long
            if (l in Int.MIN_VALUE..Int.MAX_VALUE) l.toInt() else l
        }
        element.content.toDoubleOrNull() != null -> element.content.toDouble()
        element.content == "true" -> true
        element.content == "false" -> false
        else -> element.content
    }
    is JsonNull -> null
    is JsonArray -> element.map { jsonElementToUntypedValue(it) }
    is JsonObject -> element.mapValues { (_, value) -> jsonElementToUntypedValue(value) }
}

/**
 * Convert a Kotlin value to a kotlinx.serialization JsonElement.
 */
fun jsonValueToElement(value: Any, preserveNulls: Boolean = false): JsonElement = when (value) {
    is String -> JsonPrimitive(value)
    is Number -> JsonPrimitive(value)
    is Boolean -> JsonPrimitive(value)
    is List<*> -> if (preserveNulls) {
        JsonArray(value.map { it?.let { jsonValueToElement(it, preserveNulls = true) } ?: JsonNull })
    } else {
        JsonArray(value.mapNotNull { it?.let(::jsonValueToElement) })
    }
    is Map<*, *> -> buildJsonObject {
        value.forEach { (key, entryValue) ->
            if (key is String) {
                if (preserveNulls) {
                    put(key, entryValue?.let { jsonValueToElement(it, preserveNulls = true) } ?: JsonNull)
                } else if (entryValue != null) {
                    put(key, jsonValueToElement(entryValue))
                }
            }
        }
    }
    else -> JsonPrimitive(value.toString())
}
