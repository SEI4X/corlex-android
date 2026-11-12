package ru.corlex.internal

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertNull
import org.junit.Test

internal fun configText(version: Int = 1, value: Boolean = true) =
    """{"version":$version,"etag":"e$version","params":[{"k":"flag","t":"bool","v":$value,"r":10000}],"experiments":[]}"""

class ConfigValidationTest {
    @Test fun `documents reject coercion invalid types ranges duplicates and reserved names`() {
        val cases = listOf<(JSONObject) -> Unit>(
            { it.put("version", "1") }, { it.put("version", -1) }, { it.put("version", 1.5) },
            { it.put("version", 2147483648L) },
            { it.put("etag", "e".repeat(257)) }, { it.put("etag", "bad\u0000header") }, { it.put("etag", "bad\r\nheader") },
            { it.getJSONArray("params").getJSONObject(0).put("r", "10000") },
            { it.getJSONArray("params").getJSONObject(0).put("r", -1) },
            { it.getJSONArray("params").getJSONObject(0).put("r", 10001) },
            { it.getJSONArray("params").getJSONObject(0).put("v", "true") },
            { it.getJSONArray("params").getJSONObject(0).put("k", "__proto__") },
            { it.getJSONArray("params").getJSONObject(0).put("k", "a".repeat(129)) },
            { it.getJSONArray("params").getJSONObject(0).put("t", "unknown") },
            { it.getJSONArray("params").getJSONObject(0).put("o", JSONObject().put("android", "true")) },
            { it.getJSONArray("params").getJSONObject(0).put("o", JSONObject().put("unknown", true)) },
            { it.getJSONArray("params").put(JSONObject(it.getJSONArray("params").getJSONObject(0).toString())) },
            { it.put("params", JSONArray((0..1000).map { n -> JSONObject().put("k", "p$n").put("t", "bool").put("v", true).put("r", 0) })) },
            { it.put("experiments", JSONArray().put(JSONObject("""{"k":"ab","s":"salt","v":[{"n":"a","b":10000,"v":1}]}"""))) },
            { it.put("experiments", JSONArray().put(JSONObject("""{"k":"ab","s":"salt","v":[{"n":"a","b":5000,"v":1},{"n":"b","b":4000,"v":2}]}"""))) },
            { it.put("experiments", JSONArray().put(JSONObject("""{"k":"ab","s":"salt","v":[{"n":"a","b":5000,"v":1},{"n":"b","b":9999,"v":2}]}"""))) },
            { it.put("experiments", JSONArray().put(validExperiment()).put(validExperiment())) },
            { it.put("experiments", JSONArray().put(validExperiment().put("p", "flag")).put(validExperiment().put("k", "other").put("p", "flag"))) },
            { it.put("experiments", JSONArray().put(validExperiment().put("s", "s".repeat(129)))) },
            { it.getJSONArray("params").getJSONObject(0).put("t", "json").put("v", JSONObject().put("constructor", true)) },
        )
        cases.forEachIndexed { index, mutate ->
            val doc = JSONObject(configText())
            mutate(doc)
            assertNull("invalid document case $index must be rejected as a whole", ConfigDoc.parse(doc))
        }
    }

    private fun validExperiment() = JSONObject("""{"k":"ab","s":"salt","v":[{"n":"a","b":5000,"v":1},{"n":"b","b":10000,"v":2}]}""")
}
