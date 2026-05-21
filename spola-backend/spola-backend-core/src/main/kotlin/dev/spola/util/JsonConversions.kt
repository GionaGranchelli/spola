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
fun jsonValueToElement(value: Any): JsonElement = when (value) {
    is String -> JsonPrimitive(value)
    is Number -> JsonPrimitive(value)
    is Boolean -> JsonPrimitive(value)
    is List<*> -> JsonArray(value.map { it?.let(::jsonValueToElement) ?: JsonNull })
    is Map<*, *> -> buildJsonObject {
        value.forEach { (key, entryValue) ->
            if (key is String) {
                put(key, entryValue?.let(::jsonValueToElement) ?: JsonNull)
            }
        }
    }
    else -> JsonPrimitive(value.toString())
}
