package ru.corlex.internal

import android.util.JsonReader
import android.util.JsonToken
import org.json.JSONObject
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

internal object ConfigBounds {
    const val BYTES = 1024 * 1024
    const val DEPTH = 16
    const val KEYS = 65536
    val reserved = setOf("__proto__", "constructor", "prototype")
    fun scalar(value: String): Boolean = Charsets.UTF_8.newEncoder().canEncode(value)
}

/** Platform streaming JSON validation runs before org.json can allocate an object tree. */
internal object ConfigJson {
    fun document(bytes: ByteArray, allowReserved: Boolean = false): JSONObject? {
        if (bytes.size > ConfigBounds.BYTES) return null
        val text = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: Exception) { return null }
        return objectOf(text, allowReserved = allowReserved)
    }

    fun objectOf(text: String, cache: Boolean = false, allowReserved: Boolean = false): JSONObject? {
        val extra = if (cache) 1024 else 0
        if (text.length > ConfigBounds.BYTES + extra || !ConfigBounds.scalar(text) ||
            text.toByteArray(Charsets.UTF_8).size > ConfigBounds.BYTES + extra) return null
        return try {
            JsonReader(StringReader(text)).use { reader ->
                reader.isLenient = false
                if (reader.peek() != JsonToken.BEGIN_OBJECT) return null
                val inspector = Inspector(reader, if (cache) 17 else 16, if (cache) ConfigBounds.KEYS + 2 else ConfigBounds.KEYS).apply { rejectReserved = !allowReserved }
                if (!inspector.value(0) || reader.peek() != JsonToken.END_DOCUMENT) return null
            }
            JSONObject(text)
        } catch (_: Exception) { null }
    }

    private class Inspector(private val reader: JsonReader, private val depthLimit: Int, private val keyLimit: Int) {
        private var keys = 0
        var rejectReserved = true
        fun value(depth: Int): Boolean = when (reader.peek()) {
            JsonToken.BEGIN_OBJECT -> depth < depthLimit && objectValue(depth + 1)
            JsonToken.BEGIN_ARRAY -> depth < depthLimit && arrayValue(depth + 1)
            JsonToken.STRING -> ConfigBounds.scalar(reader.nextString())
            JsonToken.NUMBER -> reader.nextString().toDoubleOrNull()?.isFinite() == true
            JsonToken.BOOLEAN -> { reader.nextBoolean(); true }
            JsonToken.NULL -> { reader.nextNull(); true }
            else -> false
        }

        private fun objectValue(depth: Int): Boolean {
            reader.beginObject()
            val names = HashSet<String>()
            while (reader.hasNext()) {
                val name = reader.nextName()
                if (++keys > keyLimit || !ConfigBounds.scalar(name) || (rejectReserved && name in ConfigBounds.reserved) || !names.add(name)) return false
                if (!value(depth)) return false
            }
            reader.endObject()
            return true
        }

        private fun arrayValue(depth: Int): Boolean {
            reader.beginArray()
            while (reader.hasNext()) if (!value(depth)) return false
            reader.endArray()
            return true
        }
    }
}
