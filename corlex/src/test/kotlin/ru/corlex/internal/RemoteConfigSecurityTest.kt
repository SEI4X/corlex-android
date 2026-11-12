package ru.corlex.internal

import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RemoteConfigSecurityTest {
    private fun options(store: KeyValueStore, transport: Transport) =
        RemoteConfigOptions(store, transport, "https://config.example.test", "ck_test_example", "android", true, { "synthetic" }, { _, _ -> }, {})

    @Test fun `invalid disk cache and header metadata are removed rather than activated`() {
        val store = MemoryStore()
        store.set(RemoteConfig.CONFIG_KEY, JSONObject().put("etag", "bad\r\nheader").put("doc", JSONObject(configText())).toString())
        val transport = FakeTransport()
        val config = RemoteConfig(options(store, transport), directExecutor)
        config.start()
        assertEquals(false, config.get("flag", false))
        assertNull(store.get(RemoteConfig.CONFIG_KEY))
        assertNull(transport.configRequests.single())
    }

    @Test fun `network rejects permissive non JSON syntax and malformed UTF8`() {
        val values = listOf(
            "{version:1,etag:'e',params:[{k:'flag',t:'bool',v:true,r:10000}]}".toByteArray(),
            (configText() + " trailing").toByteArray(),
            configText().replace("e1", "INVALID").toByteArray().let { bytes -> bytes.map { if (it == 'I'.code.toByte()) 0xff.toByte() else it }.toByteArray() },
        )
        for (body in values) {
            val transport = FakeTransport().also { it.configResponse = HttpResult(200, null, body) }
            val store = MemoryStore()
            val config = RemoteConfig(options(store, transport), directExecutor)
            config.start()
            assertEquals(false, config.get("flag", false))
            assertNull(store.get(RemoteConfig.CONFIG_KEY))
        }
    }

    @Test fun `an old request cannot replace the cache after stop and regrant`() {
        val store = MemoryStore()
        lateinit var config: RemoteConfig
        var requests = 0
        val transport = object : Transport {
            override fun postBatch(endpoint: String, key: String, body: String) = HttpResult(202, null, ByteArray(0))
            override fun getConfig(endpoint: String, key: String, etag: String?): HttpResult {
                requests++
                val request = requests
                if (request == 1) { config.stop(wipe = true); config.start() }
                return HttpResult(200, null, configText(request, request == 1).toByteArray())
            }
        }
        config = RemoteConfig(options(store, transport), directExecutor)
        config.start()
        assertEquals(false, config.get("flag", true))
        assertEquals(2, JSONObject(store.get(RemoteConfig.CONFIG_KEY)!!).getJSONObject("doc").getInt("version"))
    }

    @Test fun `one bad listener cannot abort other listeners or a valid update`() {
        val store = MemoryStore()
        val transport = FakeTransport().also { it.configResponse = HttpResult(200, null, configText().toByteArray()) }
        val config = RemoteConfig(options(store, transport), directExecutor)
        var notified = false
        config.addListener { _, _ -> throw IllegalStateException("synthetic listener failure") }
        config.addListener { _, _ -> notified = true }
        config.start()
        assertTrue(notified)
        assertEquals(true, config.get("flag", false))
        assertFalse(store.get(RemoteConfig.CONFIG_KEY).isNullOrEmpty())
    }

    @Test fun `old queued work cannot release a new grant readiness latch or send a request`() {
        val pending = ArrayList<Runnable>()
        val executor = java.util.concurrent.Executor { pending.add(it) }
        val store = MemoryStore()
        val transport = FakeTransport().also { it.configResponse = HttpResult(200, null, configText(2, false).toByteArray()) }
        val config = RemoteConfig(options(store, transport), executor)
        config.start()
        config.stop(wipe = true)
        config.start()
        pending[0].run()
        assertTrue(transport.configRequests.isEmpty())
        assertFalse(config.awaitReady(0))
        pending[1].run()
        assertTrue(config.awaitReady(0))
        assertEquals(false, config.get("flag", true))
    }

    @Test fun `an immediate consent epoch change prevents both activation and persistence`() {
        val store = MemoryStore()
        var epoch = 0L
        val transport = object : Transport {
            override fun postBatch(endpoint: String, key: String, body: String) = HttpResult(202, null, ByteArray(0))
            override fun getConfig(endpoint: String, key: String, etag: String?): HttpResult {
                epoch++
                return HttpResult(200, null, configText().toByteArray())
            }
        }
        val opts = options(store, transport).apply { this.epoch = { epoch } }
        val config = RemoteConfig(opts, directExecutor)
        config.start()
        assertNull(store.get(RemoteConfig.CONFIG_KEY))
        assertEquals(false, config.get("flag", false))
    }

    @Test fun `semantically invalid and oversized cached documents are evicted using network policy`() {
        val invalid = JSONObject(configText()).apply { getJSONArray("params").getJSONObject(0).put("v", "not bool") }
        val entries = listOf(
            JSONObject().put("etag", "e1").put("doc", invalid).toString(),
            " ".repeat(ConfigBounds.BYTES + 1025),
            JSONObject().put("etag", "different").put("doc", JSONObject(configText())).toString(),
        )
        for (entry in entries) {
            val store = MemoryStore().also { it.set(RemoteConfig.CONFIG_KEY, entry) }
            val transport = FakeTransport()
            val config = RemoteConfig(options(store, transport), directExecutor)
            config.start()
            assertNull(store.get(RemoteConfig.CONFIG_KEY))
            assertNull(transport.configRequests.single())
            assertTrue(config.getAll().isEmpty())
        }
    }


    @Test fun `NUL DEL tab and line controls in cached validators never reach URLConnection on refresh`() {
        for (bad in listOf("bad\u0000header", "bad\u007fheader", "bad\theader", "bad\r\nheader")) {
            DeliveryHttpServer(status = { 304 }).use { server ->
                val store = MemoryStore()
                val doc = JSONObject(configText()).put("etag", bad)
                store.set(RemoteConfig.CONFIG_KEY, JSONObject().put("etag", bad).put("doc", doc).toString())
                val opts = RemoteConfigOptions(store, HttpTransport(), server.url, "ck_test_example", "android", false, { "synthetic" }, { _, _ -> }, {})
                val config = RemoteConfig(opts, directExecutor)
                config.start()
                config.refresh()
                assertNull(store.get(RemoteConfig.CONFIG_KEY))
                assertEquals(2, server.requests.size)
                assertTrue(server.etags.all { it == null })
                assertEquals(false, config.get("flag", false))
            }
        }
    }

}
