package ru.corlex.internal

import org.json.JSONArray
import org.json.JSONObject

internal data class CrashDetails(val timestamp: String, val captureErrorMessages: Boolean = false)
internal class CrashReporterPolicy(
    val device: () -> DeviceInfo,
    val enabled: () -> Boolean,
    val captureErrorMessages: Boolean = false,
)

internal object CrashPrivacy {
    const val MESSAGE = "Unhandled native exception"
    private val javaName = Regex("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*")
    private val method = Regex("[A-Za-z_$][A-Za-z0-9_$]*|<init>|<clinit>")
    private const val CAUSE = "Caused by: "
    private const val FRAME_BYTES = 16 * 1024

    fun metadata(value: Any?, limit: Int): String {
        val text = value as? String ?: return ""
        return if (Charsets.UTF_8.newEncoder().canEncode(text) && text.toByteArray().size <= limit) text else ""
    }
    fun name(value: Any?): String = (value as? String)?.takeIf { staticClass(it) } ?: "NativeError"
    private fun staticClass(value: String) = value.toByteArray().size <= 512 && javaName.matches(value)
    fun message(error: Throwable, details: Boolean): String {
        if (!details) return MESSAGE
        return try { storedMessage(error.message, true) } catch (_: Throwable) { "" }
    }
    fun storedMessage(value: Any?, details: Boolean): String =
        if (details) truncateBytes((value as? String) ?: "", CrashReport.MAX_MESSAGE) else MESSAGE

    fun cause(error: Throwable, details: Boolean): String {
        val prefix = (CAUSE + name(error.javaClass.name)).takeIf { it.toByteArray().size <= 300 } ?: (CAUSE + "NativeError")
        return if (details) truncateBytes("$prefix: ${message(error, true)}", 300) else prefix
    }
    fun frame(element: StackTraceElement): JSONObject? {
        if (!staticClass(element.className) || !method.matches(element.methodName)) return null
        val fn = "${element.className}.${element.methodName}"
        if (fn.toByteArray().size > 512) return null
        return JSONObject().put("fn", fn).also {
            sourceFile(element.fileName)?.let { file -> it.put("file", file) }
            if (element.lineNumber >= 0) it.put("line", element.lineNumber)
        }
    }
    private fun sourceFile(value: String?): String? {
        val base = value?.substringBefore('?')?.substringBefore('#')?.replace('\\', '/')?.substringAfterLast('/') ?: return null
        return base.takeIf { it.isNotEmpty() && it.toByteArray().size <= 128 && Regex("[A-Za-z0-9_$.-]+").matches(it) }
    }
    fun frames(input: JSONArray?, details: Boolean): JSONArray {
        val rows = ArrayList<JSONObject>()
        if (input != null) for (i in 0 until minOf(input.length(), CrashReport.MAX_FRAMES)) {
            val row = input.optJSONObject(i) ?: continue
            sanitizedFrame(row, details)?.let(rows::add)
        }
        val kept = ArrayList<Pair<Int, JSONObject>>()
        var left = 0; var right = rows.lastIndex; var bytes = 2
        while (left <= right) {
            val index = if (kept.size % 2 == 0) left++ else right--
            val row = rows[index]; val size = row.toString().toByteArray().size + 1
            if (bytes + size <= FRAME_BYTES) { kept.add(index to row); bytes += size }
        }
        return JSONArray().also { result -> kept.sortedBy { it.first }.forEach { result.put(it.second) } }
    }
    private fun sanitizedFrame(row: JSONObject, details: Boolean): JSONObject? {
        val fn = row.opt("fn") as? String ?: return null
        if (fn.startsWith(CAUSE)) {
            val type = fn.removePrefix(CAUSE).substringBefore(':')
            if (!staticClass(type)) return null
            val prefix = CAUSE + type
            val safe = if (details && prefix.toByteArray().size > 300) CAUSE + "NativeError"
                else if (details) truncateBytes(fn, 300) else prefix
            return JSONObject().put("fn", safe)
        }
        val split = fn.lastIndexOf('.')
        if (split <= 0 || !staticClass(fn.substring(0, split)) || !method.matches(fn.substring(split + 1)) || fn.toByteArray().size > 512) return null
        return JSONObject().put("fn", fn).also { clean ->
            sourceFile(row.opt("file") as? String)?.let { clean.put("file", it) }
            val line = row.opt("line") as? Number
            if (line != null && line.toDouble() >= 0 && line.toDouble() <= Int.MAX_VALUE && line.toDouble() % 1 == 0.0)
                clean.put("line", line.toInt())
        }
    }
}
