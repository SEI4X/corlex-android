package ru.corlex.internal

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PropsTest {
    @Test fun `long strings are cut on a character boundary`() {
        val cut = truncateBytes("я".repeat(600), 1024)
        assertEquals(512, cut.length) // 2 bytes each
        assertEquals("€€", truncateBytes("€€€", 7)) // 3 bytes each
    }

    @Test fun `plain values pass and unknown objects are dropped`() {
        val out = Props.sanitize(mapOf("a" to 1, "b" to "x", "c" to true, "d" to null, "e" to Any(), "f" to 2.5))
        assertEquals(1, out.getInt("a"))
        assertTrue(out.getBoolean("c"))
        assertTrue(out.isNull("d"))
        assertFalse(out.has("e"))
        assertEquals(2.5, out.getDouble("f"), 0.0)
    }

    @Test fun `NaN and infinity become null`() {
        val out = Props.sanitize(mapOf("a" to Double.NaN, "b" to Double.POSITIVE_INFINITY))
        assertTrue(out.isNull("a"))
        assertTrue(out.isNull("b"))
    }

    @Test fun `nesting stops at four levels, arrays count as a level`() {
        val deep = mapOf("l2" to mapOf("l3" to mapOf("l4" to mapOf("l5" to mapOf("l6" to 1)))))
        val out = Props.sanitize(deep)
        assertFalse(out.getJSONObject("l2").getJSONObject("l3").getJSONObject("l4").has("l5"))
        val allowed = Props.sanitize(mapOf("a" to listOf(listOf(listOf(1)))))
        assertEquals(1, allowed.getJSONArray("a").getJSONArray(0).getJSONArray(0).getInt(0))
        val tooDeep = Props.sanitize(mapOf("a" to listOf(listOf(listOf(listOf(1))))))
        assertTrue(tooDeep.getJSONArray("a").getJSONArray(0).getJSONArray(0).isNull(0))
    }

    @Test fun `at most 100 keys and 100 array items`() {
        val many = (1..150).associate { "k$it" to it }
        assertEquals(100, Props.sanitize(many).length())
        assertEquals(100, Props.sanitize(mapOf("a" to (1..150).toList())).getJSONArray("a").length())
    }

    @Test fun `json objects and arrays are accepted as values`() {
        val out = Props.sanitize(mapOf("frames" to JSONArray().put(JSONObject().put("fn", "a.b"))))
        assertEquals("a.b", out.getJSONArray("frames").getJSONObject(0).getString("fn"))
    }
}
