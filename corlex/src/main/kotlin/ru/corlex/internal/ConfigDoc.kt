package ru.corlex.internal

import org.json.JSONArray
import org.json.JSONObject

/**
 * Remote Config on the device: the compiled document as edge-config serves it and the evaluation
 * that must give the same answer as the server and the other SDKs (packages/domain/src/remote-config
 * and the shared vectors in packages/domain/test-vectors/remote-config.json).
 */
internal object Bucketing {
    const val BUCKETS = 10_000
    private const val FNV_OFFSET = 0x811c9dc5.toInt()
    private const val FNV_PRIME = 0x01000193

    /** FNV-1a over the UTF-8 bytes, then murmur3's finalizer so nearby inputs spread evenly. */
    private fun hash32(text: String): Long {
        var h = FNV_OFFSET
        for (byte in text.toByteArray(Charsets.UTF_8)) {
            h = (h xor (byte.toInt() and 0xff)) * FNV_PRIME
        }
        h = h xor (h ushr 16)
        h *= 0x85ebca6b.toInt()
        h = h xor (h ushr 13)
        h *= 0xc2b2ae35.toInt()
        h = h xor (h ushr 16)
        return h.toLong() and 0xffffffffL
    }

    /** The bucket (0 to 9999) of a person for a key; depends on nothing but the two strings. */
    fun bucketOf(key: String, userId: String): Int = (hash32("$key\u0000$userId") % BUCKETS).toInt()
}

internal class Param(val key: String, val value: Any?, val rollout: Int, val overrides: JSONObject?)

internal class Variant(val name: String, val bound: Int, val value: Any?)

internal class Experiment(val key: String, val salt: String, val param: String?, val variants: List<Variant>)

internal class ConfigDoc(
    val version: Int,
    val etag: String,
    val params: List<Param>,
    val experiments: List<Experiment>,
) {
    companion object {
        /** A document as edge-config serves it; coercion and malformed snapshots are rejected. */
        fun parse(json: JSONObject): ConfigDoc? = ConfigSchema.parse(json)
    }
}

internal class Assignment(val variant: String, val value: Any?)

internal object ConfigEval {
    /** The type of a value as `get` compares it with the fallback: arrays and objects differ. */
    fun kindOf(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is Boolean -> "boolean"
        is Number -> "number"
        is String -> "string"
        is JSONArray -> "array"
        is JSONObject -> "object"
        else -> "other"
    }

    /**
     * What a person gets for a parameter, or `null` when the rollout does not reach them. A platform
     * override wins whatever the rollout says; otherwise the person must be in a bucket below the rollout.
     */
    fun resolve(param: Param, userId: String, platform: String): Resolved? {
        val overrides = param.overrides
        if (overrides != null && overrides.has(platform)) return Resolved(overrides.get(platform))
        if (param.rollout >= Bucketing.BUCKETS || Bucketing.bucketOf(param.key, userId) < param.rollout) {
            return Resolved(param.value)
        }
        return null
    }

    /** The variant a person gets: the first whose upper bound is above the person's bucket. */
    fun assign(experiment: Experiment, userId: String): Assignment? {
        val bucket = Bucketing.bucketOf("ab:${experiment.key}:${experiment.salt}", userId)
        val variant = experiment.variants.firstOrNull { bucket < it.bound } ?: return null
        return Assignment(variant.name, variant.value)
    }

    class Resolved(val value: Any?)
}
