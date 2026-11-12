package ru.corlex.internal

import androidx.test.core.app.ApplicationProvider
import android.app.Application
import ru.corlex.Corlex
import ru.corlex.CorlexOptions
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.io.File
import androidx.work.Data
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.impl.utils.futures.SettableFuture
import androidx.work.impl.utils.taskexecutor.WorkManagerTaskExecutor
import kotlin.coroutines.EmptyCoroutineContext
import java.util.UUID
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DeliveryOwnershipTest {
    private val sdkKey = "ck_test_" + "x".repeat(43)
    @Test fun `headless delivery only sends existing queue without creating identity or leaving a scheduler`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val server = DeliveryHttpServer()
        val store = seed(app, server.url)
        val before = Thread.getAllStackTraces().keys.filter { it.name == "corlex" }.map { it.id }.toSet()
        try {
            assertTrue(Delivery.run(app))
            assertEquals(1, server.requests.size)
            assertNull("a worker must not mint a new identity", store.get(Identity.ANON_KEY))
            val remaining = Thread.getAllStackTraces().keys.filter { it.name == "corlex" && it.id !in before && it.isAlive }
            assertTrue("a completed worker must not leak its scheduler", remaining.isEmpty())
        } finally {
            server.close()
        }
    }

    @Test fun `opt out reaches a headless owner even before Corlex start`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val response = CountDownLatch(1)
        val server = DeliveryHttpServer(beforeResponse = { response.await(5, TimeUnit.SECONDS) })
        val store = seed(app, server.url)
        store.set(Identity.ANON_KEY, "previous-id")
        store.set(Identity.USER_KEY, "previous-user")
        store.set(RemoteConfig.CONFIG_KEY, "previous-config")
        val threads = Executors.newFixedThreadPool(2)
        try {
            val delivery = threads.submit<Boolean> { Delivery.run(app) }
            assertTrue(server.firstRequest.await(5, TimeUnit.SECONDS))
            assertNull(Corlex.clientOrNull())
            threads.submit { Corlex.optOut() }.get(3, TimeUnit.SECONDS)
            response.countDown()
            delivery.get(5, TimeUnit.SECONDS)
            assertEquals(ru.corlex.Consent.DENIED, store.consent())
            for (key in listOf(CorlexClient.KEY_SETTING, Identity.ANON_KEY, Identity.USER_KEY, RemoteConfig.CONFIG_KEY))
                assertNull(store.get(key))
            assertTrue(!queueFile(app).exists() || queueFile(app).readText().isBlank())
            assertEquals("denial must not retry or start another request", 1, server.requests.size)
        } finally { response.countDown(); server.close(); threads.shutdownNow() }
    }

    @Test fun `foreground takeover waits for old cleanup off the caller and preserves original queued ids`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val response = CountDownLatch(1)
        val server = DeliveryHttpServer(
            beforeResponse = { if (it == 1) response.await(5, TimeUnit.SECONDS) },
            status = { if (it == 1) 503 else 202 },
        )
        seed(app, server.url)
        val threads = Executors.newFixedThreadPool(2)
        try {
            val delivery = threads.submit<Boolean> { Delivery.run(app) }
            assertTrue(server.firstRequest.await(5, TimeUnit.SECONDS))
            threads.submit {
                Corlex.start(app, sdkKey, CorlexOptions(
                    endpoint = server.url, allowInsecureHttp = true, remoteConfig = false,
                    trackAppLifecycle = false, trackActivityScreens = false, crashReporting = false,
                    flushIntervalMs = 60_000,
                ))
            }.get(3, TimeUnit.SECONDS)
            val foreground = Corlex.clientOrNull()!!
            foreground.track("foreground_event")
            assertTrue(threads.submit<Boolean> { foreground.flushBlocking() }.get(5, TimeUnit.SECONDS))
            response.countDown()
            delivery.get(5, TimeUnit.SECONDS)
            val last = org.json.JSONObject(server.requests.last()).getJSONArray("events").objects()
            assertTrue(last.any { it.getString("event_id") == uuid(1) })
            assertTrue(last.any { it.getString("name") == "foreground_event" })
            assertTrue(!queueFile(app).exists() || queueFile(app).readText().isBlank())
            // Worker completion must leave the foreground owner's executor usable.
            foreground.track("after_worker")
            assertTrue(foreground.flushBlocking())
        } finally { response.countDown(); stopForeground(); server.close(); threads.shutdownNow() }
    }

    @Test fun `stopping a real Worker releases its owner and preserves queued ids for the next worker`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val response = CountDownLatch(1)
        val server = DeliveryHttpServer(
            beforeResponse = { if (it == 1) response.await(5, TimeUnit.SECONDS) },
            status = { if (it == 1) 503 else 202 },
        )
        seed(app, server.url)
        val threads = Executors.newSingleThreadExecutor()
        val worker = DeliveryWorker(app, workerParameters())
        try {
            val running = threads.submit { worker.doWork() }
            assertTrue(server.firstRequest.await(5, TimeUnit.SECONDS))
            worker.onStopped()
            response.countDown()
            running.get(5, TimeUnit.SECONDS)
            assertEquals(uuid(1), EventQueue(queueFile(app)).peek(1).single().getString("event_id"))
            assertTrue(Delivery.run(app))
            assertEquals(uuid(1), org.json.JSONObject(server.requests.last()).getJSONArray("events").getJSONObject(0).getString("event_id"))
        } finally { response.countDown(); server.close(); threads.shutdownNow() }
    }

    @Test fun `headless never sends with a missing grant or unapproved HTTP override`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        DeliveryHttpServer().use { server ->
            val store = seed(app, server.url)
            val privacy = File(store.disk.root, "privacy.json")
            val original = privacy.readText()
            privacy.writeText(org.json.JSONObject(original).apply { remove("state") }.toString())
            assertTrue(Delivery.run(app))
            privacy.writeText(original)
            val authFile = File(store.disk.root, "auth.json")
            authFile.writeText(org.json.JSONObject(authFile.readText()).put("allowInsecureHttp", false)
                .put("endpoint", "http://192.0.2.1").toString())
            assertTrue(Delivery.run(app))
            assertTrue(server.requests.isEmpty())
            assertEquals(uuid(1), EventQueue(queueFile(app)).peek(1).single().getString("event_id"))
        }
    }

    @Test fun `a stopped worker borrowing the foreground client never closes that client`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val response = CountDownLatch(1)
        val server = DeliveryHttpServer(beforeResponse = { if (it == 1) response.await(5, TimeUnit.SECONDS) })
        val threads = Executors.newSingleThreadExecutor()
        try {
            Corlex.start(app, sdkKey, CorlexOptions(
                endpoint = server.url, allowInsecureHttp = true, remoteConfig = false,
                trackAppLifecycle = false, trackActivityScreens = false, crashReporting = false,
                flushIntervalMs = 60_000,
            ))
            val foreground = Corlex.clientOrNull()!!
            foreground.track("before_worker")
            val worker = DeliveryWorker(app, workerParameters())
            val running = threads.submit { worker.doWork() }
            assertTrue(server.firstRequest.await(5, TimeUnit.SECONDS))
            worker.onStopped()
            response.countDown()
            running.get(5, TimeUnit.SECONDS)
            foreground.track("after_borrowed_worker")
            assertTrue(foreground.flushBlocking())
            assertEquals("after_borrowed_worker", org.json.JSONObject(server.requests.last()).getJSONArray("events").getJSONObject(0).getString("name"))
        } finally { response.countDown(); stopForeground(); server.close(); threads.shutdownNow() }
    }

    @Test fun `headless opt out cannot resurrect a grant during immediate foreground takeover`() = takeoverAfterDeny(false)

    @Test fun `an explicit opt in during takeover waits until previous denial cleanup completes`() = takeoverAfterDeny(true)

    private fun takeoverAfterDeny(grant: Boolean) {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val response = CountDownLatch(1)
        val blocked = CountDownLatch(1)
        val cleanup = CountDownLatch(1)
        val server = DeliveryHttpServer(beforeResponse = { response.await(5, TimeUnit.SECONDS) })
        val store = seed(app, server.url)
        val threads = Executors.newSingleThreadExecutor()
        try {
            val delivery = threads.submit<Boolean> { Delivery.run(app) }
            assertTrue(server.firstRequest.await(5, TimeUnit.SECONDS))
            activeHeadlessClient().post { blocked.countDown(); cleanup.await(5, TimeUnit.SECONDS) }
            Corlex.optOut()
            assertTrue(blocked.await(3, TimeUnit.SECONDS))
            Corlex.start(app, sdkKey, CorlexOptions(
                endpoint = server.url, allowInsecureHttp = true, remoteConfig = false,
                trackAppLifecycle = false, trackActivityScreens = false, crashReporting = false,
            ))
            if (grant) Corlex.optIn()
            assertTrue("startup must stay ungranted until old denial is durable", Corlex.consent() != ru.corlex.Consent.GRANTED)
            assertNull("takeover must not create identity before old cleanup", store.get(Identity.ANON_KEY))
            cleanup.countDown()
            response.countDown()
            delivery.get(5, TimeUnit.SECONDS)
            val foreground = Corlex.clientOrNull()!!
            foreground.track(if (grant) "new_granted_event" else "must_not_collect")
            foreground.flushBlocking()
            assertEquals(if (grant) ru.corlex.Consent.GRANTED else ru.corlex.Consent.DENIED, Corlex.consent())
            assertEquals(grant, BindingDisk(File(app.noBackupFilesDir, "corlex")).workerAuth() != null)
            assertFalse("old captured scope must stay erased", File(store.directory, "state.json").exists())
            if (grant) {
                val events = org.json.JSONObject(server.requests.last()).getJSONArray("events").objects()
                assertEquals(listOf("new_granted_event"), events.map { it.getString("name") })
                assertTrue(events.none { it.getString("event_id") == uuid(1) })
            } else {
                assertNull(store.get(Identity.ANON_KEY))
                assertNull(store.get(CorlexClient.KEY_SETTING))
            }
            assertEquals(if (grant) 2 else 1, server.requests.size)
        } finally { cleanup.countDown(); response.countDown(); stopForeground(); server.close(); threads.shutdownNow() }
    }

    private fun activeHeadlessClient(): CorlexClient {
        val owner = Delivery::class.java.getDeclaredField("headless").also { it.isAccessible = true }.get(null)
        return owner.javaClass.getDeclaredField("client").also { it.isAccessible = true }.get(owner) as CorlexClient
    }

    private fun workerParameters(): WorkerParameters {
        val completed = { SettableFuture.create<Void>().apply { set(null) } }
        return WorkerParameters(
            UUID.randomUUID(), Data.EMPTY, emptyList(), WorkerParameters.RuntimeExtras(), 0, 0,
            directExecutor, EmptyCoroutineContext, WorkManagerTaskExecutor(directExecutor),
            object : WorkerFactory() {
                override fun createWorker(context: android.content.Context, name: String, params: WorkerParameters) = null
            },
            { _, _, _ -> completed() }, { _, _, _ -> completed() },
        )
    }

    private fun queueFile(app: Application): File {
        val root = File(app.noBackupFilesDir, "corlex")
        val auth = BindingDisk(root).workerAuth() ?: return File(root, "queue.jsonl")
        return File(root, "scopes/${auth.getString("owner")}/${auth.getString("generation")}/queue.jsonl")
    }

    private fun seed(app: Application, endpoint: String): BoundStorage {
        val disk = BindingDisk(File(app.noBackupFilesDir, "corlex"))
        val options = CorlexOptions(endpoint = endpoint, allowInsecureHttp = true, remoteConfig = false)
        val owner = bindingIdentity(sdkKey, options)!!
        val store = disk.open(owner, ru.corlex.Consent.GRANTED)
        store.guard {
            disk.write(File(disk.root, "auth.json"), org.json.JSONObject()
                .put("owner", owner).put("generation", store.generation).put("key", sdkKey)
                .put("endpoint", endpoint).put("configEndpoint", options.configEndpoint)
                .put("batchSize", 20).put("allowInsecureHttp", true))
        }
        EventQueue(queueFile(app), binding = store).push(event(uuid(1), "app_open"))
        return store
    }

    private fun stopForeground() {
        Corlex.clientOrNull()?.let { client ->
            Corlex.optOut()
            client.close().get(5, TimeUnit.SECONDS)
            client.releaseExecutors()
        }
        Corlex::class.java.getDeclaredField("runtime").also { it.isAccessible = true }.set(null, null)
    }
}
