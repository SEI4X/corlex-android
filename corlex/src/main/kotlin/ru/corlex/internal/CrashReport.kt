package ru.corlex.internal

import org.json.JSONArray
import org.json.JSONObject

/**
 * The `corlex.crash` payload of a Java/Kotlin exception, in the shape the mobile contract fixes
 * (services/ingest/docs/protocol.md): frames exactly as `Throwable.getStackTrace()` gives them, so
 * they are obfuscated in a minified app and the server retraces them with the uploaded mapping.txt.
 */
internal object CrashReport {
    const val MAX_MESSAGE = 512
    const val MAX_FRAMES = 100
    private const val MAX_CAUSES = 5

    /** The JSON file written at crash time; [timestamp] is when it happened, not when it is sent. */
    fun build(error: Throwable, device: DeviceInfo, timestamp: String): JSONObject =
        build(error, device, CrashDetails(timestamp))

    fun build(error: Throwable, device: DeviceInfo, details: CrashDetails): JSONObject = JSONObject()
        .put("v", 1).put("crash_id", newUuid()).put("timestamp", details.timestamp)
        .put("fatal", true).put("source", "java_exception")
        .put("name", CrashPrivacy.name(error.javaClass.name))
        .put("message", CrashPrivacy.message(error, details.captureErrorMessages))
        .put("frames", frames(error, details.captureErrorMessages))
        .put("app_version", CrashPrivacy.metadata(device.appVersion, 128))
        .put("build", CrashPrivacy.metadata(device.build, 32))

    /** The properties of the event sent at the next launch. */
    fun toProperties(report: JSONObject, details: Boolean = false): Map<String, Any?> = mapOf(
        "crash_id" to report.getString("crash_id"),
        "kind" to "native",
        "fatal" to report.optBoolean("fatal", true),
        "name" to CrashPrivacy.name(report.opt("name")),
        "message" to CrashPrivacy.storedMessage(report.opt("message"), details),
        "source" to "java_exception",
        "frames" to CrashPrivacy.frames(report.optJSONArray("frames"), details),
        "symbolicated" to false,
    )

    /**
     * `[{fn, file, line}]`, innermost first, at most [MAX_FRAMES]. The `Caused by:` chain follows,
     * each cause behind a frame `{fn: "Caused by: <class>: <message>"}`. When the chain does not fit,
     * each exception keeps an equal share of the budget (the unused part goes to the longer ones), so
     * a deep UI stack on top never pushes the root cause out.
     */
    fun frames(error: Throwable, details: Boolean = false): JSONArray {
        val chain = chainOf(error)
        val shares = allocate(chain.map { it.stackTrace.size }, MAX_FRAMES - (chain.size - 1))
        val out = JSONArray()
        chain.forEachIndexed { index, throwable ->
            if (index > 0) out.put(JSONObject().put("fn", CrashPrivacy.cause(throwable, details)))
            throwable.stackTrace.take(shares[index]).forEach { CrashPrivacy.frame(it)?.let(out::put) }
        }
        return CrashPrivacy.frames(out, details)
    }

    private fun chainOf(error: Throwable): List<Throwable> {
        val seen = java.util.IdentityHashMap<Throwable, Boolean>()
        val chain = ArrayList<Throwable>()
        var current: Throwable? = error
        while (current != null && chain.size <= MAX_CAUSES && seen.put(current, true) == null) {
            chain.add(current)
            current = current.cause
        }
        return chain
    }

    /** Splits [budget] between stacks of the given [sizes]: equal shares, leftovers to those that need more. */
    internal fun allocate(sizes: List<Int>, budget: Int): IntArray {
        val shares = IntArray(sizes.size)
        var remaining = budget.coerceAtLeast(0)
        var open = sizes.indices.filter { sizes[it] > 0 }
        while (remaining > 0 && open.isNotEmpty()) {
            val each = maxOf(1, remaining / open.size)
            for (i in open) {
                val give = minOf(each, sizes[i] - shares[i], remaining)
                shares[i] += give
                remaining -= give
            }
            open = open.filter { shares[it] < sizes[it] }
        }
        return shares
    }

}
