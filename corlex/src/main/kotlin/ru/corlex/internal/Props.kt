package ru.corlex.internal

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Cuts a string to at most [max] UTF-8 bytes without splitting a character. */
internal fun truncateBytes(text: String, max: Int): String {
    if (text.length * 3 <= max) return text
    var bytes = 0
    var index = 0
    while (index < text.length) {
        val codePoint = text.codePointAt(index)
        val size = when {
            codePoint < 0x80 -> 1
            codePoint < 0x800 -> 2
            codePoint < 0x10000 -> 3
            else -> 4
        }
        if (bytes + size > max) return text.substring(0, index)
        bytes += size
        index += Character.charCount(codePoint)
    }
    return text
}

internal fun newUuid(): String = UUID.randomUUID().toString()

/**
 * Makes a value fit the protocol: long strings are cut, what cannot be represented (unknown
 * objects, too deep nesting) is dropped. Depth follows the Go validator: `properties` itself is
 * level 1 and an array counts as a level. Same rules as packages/sdk-core/src/properties.ts.
 */
internal object Props {
    fun sanitize(input: Map<String, Any?>?): JSONObject = if (input == null) JSONObject() else cleanMap(input, 1)

    /** `null` means "drop this value". */
    private fun clean(value: Any?, depth: Int): Any? = when (value) {
        null, JSONObject.NULL -> JSONObject.NULL
        is Boolean -> value
        is Number -> finiteOrNull(value)
        is CharSequence -> truncateBytes(value.toString(), Limits.MAX_PROPERTY_STRING)
        is Char -> value.toString()
        is Enum<*> -> value.name
        is Map<*, *> -> if (depth + 1 > Limits.MAX_DEPTH) null else cleanMap(value, depth + 1)
        is JSONObject -> if (depth + 1 > Limits.MAX_DEPTH) null else cleanMap(jsonToMap(value), depth + 1)
        is JSONArray -> cleanArray(jsonToList(value), depth)
        is Iterable<*> -> cleanArray(value.toList(), depth)
        is Array<*> -> cleanArray(value.toList(), depth)
        else -> null
    }

    private fun finiteOrNull(number: Number): Any =
        if (number.toDouble().isNaN() || number.toDouble().isInfinite()) JSONObject.NULL else number

    private fun cleanArray(items: List<Any?>, depth: Int): JSONArray? {
        if (depth + 1 > Limits.MAX_DEPTH) return null
        val out = JSONArray()
        for (item in items.take(Limits.MAX_ARRAY)) out.put(clean(item, depth + 1) ?: JSONObject.NULL)
        return out
    }

    private fun cleanMap(map: Map<*, *>, depth: Int): JSONObject {
        val out = JSONObject()
        var count = 0
        for ((rawKey, raw) in map) {
            val key = truncateBytes(rawKey.toString(), Limits.MAX_PROPERTY_KEY)
            if (key.isEmpty() || count >= Limits.MAX_PROPERTIES) continue
            val value = clean(raw, depth) ?: continue
            out.put(key, value)
            count += 1
        }
        return out
    }

    private fun jsonToMap(json: JSONObject): Map<String, Any?> =
        json.keys().asSequence().associateWith { json.opt(it) }

    private fun jsonToList(json: JSONArray): List<Any?> = (0 until json.length()).map { json.opt(it) }
}
