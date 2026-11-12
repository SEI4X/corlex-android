package ru.corlex.internal

import org.json.JSONArray
import org.json.JSONObject

internal object ConfigSchema {
    private val platforms = setOf("ios", "android", "web", "flutter", "react-native")
    private fun text(value: Any?, max: Int): Boolean = value is String && value.isNotEmpty() && value.length <= max && ConfigBounds.scalar(value)
    private fun key(value: Any?): Boolean = text(value, 128) && value !in ConfigBounds.reserved
    private fun integer(value: Any?, max: Int): Boolean = value is Number && value.toDouble().let { it.isFinite() && it >= 0 && it <= max && it % 1 == 0.0 }
    fun etag(value: Any?): Boolean = text(value, 256) && (value as String).all { it.code in 32..126 }

    fun parse(json: JSONObject): ConfigDoc? { return try {
        val version = json.opt("version")
        val tag = json.opt("etag")
        val params = json.opt("params") as? JSONArray ?: return null
        val experiments = if (!json.has("experiments")) JSONArray() else json.opt("experiments") as? JSONArray ?: return null
        if (!integer(version, Int.MAX_VALUE) || !etag(tag) || params.length() > 1000 || experiments.length() > 1000 ||
            json.toString().toByteArray(Charsets.UTF_8).size > ConfigBounds.BYTES || !safeJson(json, 0, intArrayOf(0))) return null
        val parsedParams = objects(params).map { param(it) ?: return null }
        val parsedExperiments = objects(experiments).map { experiment(it) ?: return null }
        if (!unique(parsedParams.map { it.key }) || !unique(parsedExperiments.map { it.key }) ||
            !unique(parsedExperiments.mapNotNull { it.param })) return null
        ConfigDoc((version as Number).toInt(), tag as String, parsedParams, parsedExperiments)
    } catch (_: Exception) { null } }

    private fun objects(array: JSONArray): List<JSONObject> = (0 until array.length()).map { array.getJSONObject(it) }
    private fun unique(values: List<String>) = values.size == values.toSet().size
    private fun matches(type: Any?, value: Any?): Boolean = when (type) {
        "bool" -> value is Boolean
        "number" -> value is Number && value.toDouble().isFinite()
        "string" -> value is String
        "json" -> safeJson(value, 0, intArrayOf(0))
        else -> false
    }

    private fun param(json: JSONObject): Param? {
        val name = json.opt("k")
        val value = json.opt("v")
        val rollout = json.opt("r")
        val type = json.opt("t")
        if (!key(name) || !json.has("v") || !integer(rollout, 10000) || !matches(type, value)) return null
        val overrides = if (!json.has("o")) null else (json.opt("o") as? JSONObject ?: return null)
        if (overrides != null && overrides.keys().asSequence().any { it !in platforms || !matches(type, overrides.get(it)) }) return null
        return Param(name as String, value, (rollout as Number).toInt(), overrides)
    }

    private fun experiment(json: JSONObject): Experiment? {
        val name = json.opt("k")
        val salt = json.opt("s")
        val param = if (!json.has("p")) null else (json.opt("p").takeIf(::key) as? String ?: return null)
        val variants = json.opt("v") as? JSONArray ?: return null
        if (!key(name) || !text(salt, 128) || variants.length() !in 2..10) return null
        val parsed = objects(variants).map { variant(it) ?: return null }
        if (!unique(parsed.map { it.name }) || parsed.last().bound != 10000 ||
            parsed.zipWithNext().any { (a, b) -> a.bound >= b.bound }) return null
        return Experiment(name as String, salt as String, param, parsed)
    }

    private fun variant(json: JSONObject): Variant? {
        val name = json.opt("n")
        val bound = json.opt("b")
        if (!text(name, 128) || !integer(bound, 10000) || (bound as Number).toInt() == 0 || !json.has("v")) return null
        return Variant(name as String, bound.toInt(), json.opt("v"))
    }

    private fun safeJson(value: Any?, depth: Int, keys: IntArray): Boolean {
        if (depth > ConfigBounds.DEPTH || (depth >= ConfigBounds.DEPTH && (value is JSONObject || value is JSONArray))) return false
        return when (value) {
            null, JSONObject.NULL -> true
            is String -> ConfigBounds.scalar(value)
            is Boolean -> true
            is Number -> value.toDouble().isFinite()
            is JSONArray -> (0 until value.length()).all { safeJson(value.get(it), depth + 1, keys) }
            is JSONObject -> value.keys().asSequence().all {
                ++keys[0] <= ConfigBounds.KEYS && it !in ConfigBounds.reserved && ConfigBounds.scalar(it) && safeJson(value.get(it), depth + 1, keys)
            }
            else -> false
        }
    }
}
