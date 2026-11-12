package ru.corlex.internal

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 35])
class ConfigEtagTest {
    private val invalid = listOf("é", "Привет", "😄", "bad\u0080header")
    private fun options(store: KeyValueStore, transport: Transport, endpoint: String = "https://config.example.test") =
        RemoteConfigOptions(store, transport, endpoint, "ck_test_example", "android", false, { "synthetic" }, { _, _ -> }, {})

    @Test fun `only nonempty printable ASCII is accepted without restricting ordinary JSON strings`() {
        for (bad in invalid) assertFalse(ConfigSchema.etag(bad))
        assertTrue(ConfigSchema.etag((32..126).map(Int::toChar).joinToString("")))
        assertFalse(ConfigSchema.etag("")); assertFalse(ConfigSchema.etag("e".repeat(257)))
        val doc = JSONObject(configText()).apply {
            getJSONArray("params").getJSONObject(0).put("t", "string").put("v", "Привет 😀")
        }
        assertNotNull(ConfigDoc.parse(doc))
    }
    @Test fun `downloaded Unicode validators never become active or persisted`() {
        for (bad in invalid) {
            val doc = JSONObject(configText()).put("etag", bad)
            val transport = FakeTransport().apply { configResponse = HttpResult(200, null, doc.toString().toByteArray()) }
            val store = MemoryStore(); val config = RemoteConfig(options(store, transport), directExecutor)
            config.start(); config.refresh()
            assertNull(store.get(RemoteConfig.CONFIG_KEY))
            assertEquals(false, config.get("flag", false))
            assertTrue(transport.configRequests.all { it == null })
        }
    }
    @Test fun `corrupt cached Unicode validators are evicted before real HTTP refresh`() {
        for (bad in invalid) DeliveryHttpServer(status = { 304 }).use { server ->
            val store = MemoryStore()
            val doc = JSONObject(configText()).put("etag", bad)
            store.set(RemoteConfig.CONFIG_KEY, JSONObject().put("etag", bad).put("doc", doc).toString())
            val config = RemoteConfig(options(store, HttpTransport(), server.url), directExecutor)
            config.start(); config.refresh()
            assertNull(store.get(RemoteConfig.CONFIG_KEY))
            assertEquals(2, server.requests.size)
            assertTrue(server.etags.all { it == null })
        }
    }
    @Test fun `valid ASCII cached validator is actually sent and remains usable after 304`() {
        DeliveryHttpServer(status = { 304 }).use { server ->
            val tag = "\"v1-stable\""
            val store = MemoryStore()
            val doc = JSONObject(configText()).put("etag", tag)
            store.set(RemoteConfig.CONFIG_KEY, JSONObject().put("etag", tag).put("doc", doc).toString())
            val config = RemoteConfig(options(store, HttpTransport(), server.url), directExecutor)
            config.start(); config.refresh()
            assertEquals(listOf(tag, tag), server.etags.toList())
            assertNotNull(store.get(RemoteConfig.CONFIG_KEY))
            assertEquals(true, config.get("flag", false))
        }
    }
}
