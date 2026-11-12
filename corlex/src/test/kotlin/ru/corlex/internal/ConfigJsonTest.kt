package ru.corlex.internal

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 35])
class ConfigJsonTest {
    @Test fun `streaming guard bounds bytes depth and keys before object construction`() {
        assertNull(ConfigJson.document(ByteArray(ConfigBounds.BYTES + 1) { ' '.code.toByte() }))
        val depth16 = "{\"version\":0,\"etag\":\"e\",\"params\":[],\"extra\":" + "[".repeat(15) + "0" + "]".repeat(15) + "}"
        assertNotNull(ConfigJson.objectOf(depth16))
        assertNull(ConfigJson.objectOf(depth16.replace("0]", "[0]]")))
        val keys = (0..65536).joinToString(",") { "\"k$it\":0" }
        assertNull(ConfigJson.objectOf("{\"extra\":{$keys}}"))
    }

    @Test fun `duplicate escaped reserved and malformed JSON members are rejected`() {
        val invalid = listOf(
            """{"version":1,"version":2}""", """{"version":1,"ver\u0073ion":2}""",
            """{"__proto__":1}""", """{"a":{"constructor":2}}""", """{"a":{"prototype":2}}""",
            """{unquoted:1}""", """{'single':1}""", """{"x":1,}""", """{"x":[1,]}""",
            """{"x":NaN}""", """{"x":Infinity}""", """{"x":1e999}""", """{"x":01}""",
            """{"x":0x10}""", """{"x":"\ud800"}""", """{"x":1} garbage""",
        )
        invalid.forEachIndexed { index, value -> assertNull("strict JSON case $index", ConfigJson.objectOf(value)) }
        assertNotNull(ConfigJson.objectOf("""{"x":"😀","escaped":"\ud83d\ude00"}"""))
    }
}
