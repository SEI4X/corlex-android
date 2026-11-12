package ru.corlex.internal

import androidx.test.core.app.ApplicationProvider
import android.app.Application
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ru.corlex.Corlex
import ru.corlex.CorlexOptions
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ClientBindingTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val clients = ArrayList<CorlexClient>()
    private val keyA = "ck_test_" + "a".repeat(43)
    private val keyB = "ck_test_" + "b".repeat(43)
    private fun options(endpoint: String, config: String = "https://config.example.test") = CorlexOptions(
        endpoint = endpoint, configEndpoint = config, remoteConfig = false,
        flushIntervalMs = 60_000, crashReporting = false, trackAppLifecycle = false, trackActivityScreens = false,
    )
    private fun client(key: String, options: CorlexOptions): CorlexClient =
        CorlexClient(key, options, AndroidDeps.create(app, false)).also { clients.add(it); it.start() }
    private fun settle(client: CorlexClient) {
        val ready = CountDownLatch(1)
        client.post { ready.countDown() }
        assertTrue(ready.await(5, TimeUnit.SECONDS))
    }
    private fun stop(client: CorlexClient) { client.close().get(5, TimeUnit.SECONDS); client.releaseExecutors(); clients.remove(client) }
    @After fun cleanup() {
        clients.toList().forEach(::stop)
        Corlex.clientOrNull()?.let { stop(it) }
        Corlex::class.java.getDeclaredField("runtime").also { it.isAccessible = true }.set(null, null)
    }

    @Test fun `key B never replays queued events or identity of key A`() {
        DeliveryHttpServer().use { server ->
            val first = client(keyA, options(server.url))
            first.identify("a-user"); first.track("only_a"); settle(first)
            val root = File(app.noBackupFilesDir, "corlex")
            assertTrue(File(root, "auth.json").readText().contains(keyA))
            assertTrue(File(root, "scopes").walkTopDown().filter { it.isFile }.none { it.readText().contains(keyA) })
            assertTrue(app.getSharedPreferences(PrefsStore.FILE, 0).all.isEmpty())
            stop(first)
            val second = client(keyB, options(server.url))
            second.track("only_b"); second.flushBlocking()
            val events = JSONObject(server.requests.single()).getJSONArray("events").objects()
            assertEquals(listOf("only_b"), events.map { it.getString("name") })
            assertFalse(events.single().has("user_id"))
        }
    }
    @Test fun `ingest and config proxy base paths each isolate offline history`() {
        DeliveryHttpServer().use { server ->
            var first = client(keyA, options(server.url + "/cell-a"))
            first.track("old_ingest_cell"); settle(first); stop(first)
            val second = client(keyA, options(server.url + "/cell-b"))
            second.track("new_ingest_cell"); second.flushBlocking(); stop(second)
            assertEquals(listOf("new_ingest_cell"), JSONObject(server.requests.last()).getJSONArray("events").objects().map { it.getString("name") })
            first = client(keyA, options(server.url, "https://config.example.test/cell-a"))
            first.track("old_config_cell"); settle(first); stop(first)
            val last = client(keyA, options(server.url, "https://config.example.test/cell-b"))
            last.track("new_config_cell"); last.flushBlocking()
            assertEquals(listOf("new_config_cell"), JSONObject(server.requests.last()).getJSONArray("events").objects().map { it.getString("name") })
        }
    }
    @Test fun `same binding restart preserves original offline event id`() {
        DeliveryHttpServer().use { server ->
            val first = client(keyA, options(server.url))
            first.capture("restart_event", null, CaptureMeta(eventId = uuid(1))); settle(first); stop(first)
            val second = client(keyA, options(server.url))
            second.flushBlocking()
            val event = JSONObject(server.requests.single()).getJSONArray("events").getJSONObject(0)
            assertEquals("restart_event", event.getString("name"))
            assertEquals(uuid(1), event.getString("event_id"))
        }
    }
    @Test fun `other live client cannot send an old memory queue after global opt out`() {
        DeliveryHttpServer().use { server ->
            val first = client(keyA, options(server.url))
            first.track("old_a"); settle(first)
            val second = client(keyB, options(server.url)); settle(second)
            second.optOut(); settle(second)
            first.flushBlocking()
            assertEquals(0, server.requests.size)
            second.optIn(); settle(second)
            assertEquals(ru.corlex.Consent.DENIED, first.consent())
            first.track("must_not_resume_old_owner"); first.flushBlocking()
            assertEquals(0, server.requests.size)
        }
    }
    @Test fun `unreadable protected privacy cannot default a fresh credential to granted`() {
        val root = File(app.noBackupFilesDir, "corlex").apply { mkdirs() }
        File(root, "privacy.json").writeText("not-readable-privacy")
        DeliveryHttpServer().use { server ->
            val client = client(keyB, options(server.url))
            client.track("must_not_send"); client.flushBlocking()
            assertEquals(ru.corlex.Consent.PENDING, client.consent())
            assertEquals(0, server.requests.size)
        }
    }
    @Test fun `cached config getters never wait behind a blocked SDK disk operation`() {
        val client = client(keyA, options("https://ingest.example.test")); settle(client)
        val disk = BindingDisk(File(app.noBackupFilesDir, "corlex"))
        val scope = disk.open(bindingIdentity(keyA, options("https://ingest.example.test"))!!, ru.corlex.Consent.GRANTED)
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val threads = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val pending = threads.submit { scope.guard { entered.countDown(); release.await() } }
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            assertEquals(false, threads.submit<Any> { client.remote!!.get("flag", false) }.get(1, TimeUnit.SECONDS))
            disk.fenceDenied()
            assertEquals(ru.corlex.Consent.DENIED, client.consent())
            release.countDown(); pending.get(3, TimeUnit.SECONDS)
        } finally { release.countDown(); threads.shutdownNow() }
    }
    @Test fun `pending and denied regrant keep config subscriptions while replacing persistence`() {
        val executor = java.util.concurrent.ScheduledThreadPoolExecutor(1)
        val fake = FakeTransport().apply { configResponse = HttpResult(200, null,
            "{\"version\":1,\"etag\":\"e1\",\"params\":[]}".toByteArray()) }
        val deps = ClientDeps(MemoryStore(), null, fake, { testDevice }, executor, directExecutor, {}, {},
            bindingDisk = BindingDisk(File(app.noBackupFilesDir, "corlex")))
        val client = CorlexClient(keyA, CorlexOptions(consent = ru.corlex.Consent.PENDING), deps)
        clients.add(client); client.start(); settle(client)
        val remote = client.remote!!
        var updates = 0
        remote.addListener { _, _ -> updates++ }
        client.optIn(); settle(client)
        assertSame(remote, client.remote)
        assertEquals(1, updates)
        client.optOut(); settle(client)
        client.optIn(); settle(client)
        assertSame(remote, client.remote)
        assertEquals(2, updates)
    }
    @Test fun `pending bound queues remain capped without enabling captured data persistence`() {
        val disk = BindingDisk(File(app.noBackupFilesDir, "corlex"))
        val scope = disk.open(bindingIdentity(keyA, options("https://ingest.example.test"))!!, ru.corlex.Consent.PENDING)
        val queue = EventQueue(null, maxEvents = 2, binding = scope)
        for (i in 1..3) queue.push(event(uuid(i)))
        assertEquals(2, queue.size())
        assertEquals(listOf(uuid(2), uuid(3)), queue.peek(3).map { it.getString("event_id") })
        assertFalse(File(scope.directory, "queue.jsonl").exists())
    }
    @Test fun `config readiness waits for protected initialization and exposes an early subscription facade`() {
        val barrier = java.util.concurrent.FutureTask<Unit> { Unit }
        val executor = java.util.concurrent.ScheduledThreadPoolExecutor(1)
        val deps = ClientDeps(MemoryStore(), null, FakeTransport(), { testDevice }, executor, directExecutor, {}, {},
            startupBarrier = barrier, bindingDisk = BindingDisk(File(app.noBackupFilesDir, "corlex")))
        val client = CorlexClient(keyA, options("https://ingest.example.test"), deps)
        clients.add(client)
        try {
            client.start()
            assertNotNull(client.remote)
            assertFalse(client.awaitConfigReady(1))
            barrier.run()
            assertTrue(client.awaitConfigReady(1000))
        } finally { barrier.run() }
    }
    @Test fun `memory only activation cannot persist worker credentials when disk recovers`() {
        val root = File(app.noBackupFilesDir, "corlex").apply { writeText("blocked-directory") }
        val executor = java.util.concurrent.ScheduledThreadPoolExecutor(1)
        val deps = ClientDeps(MemoryStore(), null, FakeTransport(), { testDevice }, executor, directExecutor, {}, {},
            bindingDisk = BindingDisk(root))
        val storage = ClientStorage(deps, keyA to options("https://ingest.example.test")) {}
        try {
            storage.initialize(); storage.activate()
            assertFalse(storage.scope!!.persistent)
            root.delete(); root.mkdirs()
            storage.save()
            assertFalse(File(root, "auth.json").exists())
        } finally { storage.close(); executor.shutdownNow() }
    }
    @Test fun `MCP and upload credentials are rejected before SDK state is created`() {
        for (invalid in listOf("cm_example", "corlex_up_example", "sk_example")) {
            Corlex.start(app, invalid, options("https://ingest.example.test"))
            assertNull(Corlex.clientOrNull())
            assertTrue(app.getSharedPreferences(PrefsStore.FILE, 0).all.isEmpty())
            assertFalse(File(app.noBackupFilesDir, "corlex").exists())
        }
    }
}
