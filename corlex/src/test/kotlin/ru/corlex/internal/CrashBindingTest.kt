package ru.corlex.internal

import android.app.Application
import androidx.test.core.app.ApplicationProvider
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
class CrashBindingTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val keyA = "ck_test_" + "a".repeat(43)
    private val keyB = "ck_test_" + "b".repeat(43)
    private fun start(key: String, endpoint: String, details: Boolean = false): CorlexClient {
        Corlex.start(app, key, CorlexOptions(endpoint = endpoint, remoteConfig = false,
            flushIntervalMs = 60_000, trackAppLifecycle = false, trackActivityScreens = false, captureErrorMessages = details))
        val client = Corlex.clientOrNull()!!
        repeat(2) { val done = CountDownLatch(1); client.post { done.countDown() }; assertTrue(done.await(5, TimeUnit.SECONDS)) }
        return client
    }
    private fun reporter(): CrashReporter {
        val runtime = Corlex::class.java.getDeclaredField("runtime").also { it.isAccessible = true }.get(null)
        return runtime.javaClass.getDeclaredField("crash").also { it.isAccessible = true }.get(runtime) as CrashReporter
    }
    private fun record(): String {
        reporter().record(IllegalStateException("synthetic-details"))
        val file = File(app.noBackupFilesDir, "corlex").walkTopDown()
            .filter { it.isFile && it.name.startsWith("crash-") && it.extension == "json" }.single()
        val row = JSONObject(file.readText())
        return (row.optJSONObject("report") ?: row).getString("crash_id")
    }
    @After fun close() {
        val client = Corlex.clientOrNull() ?: return
        reporter().uninstall()
        client.close().get(5, TimeUnit.SECONDS); client.releaseExecutors()
        Corlex::class.java.getDeclaredField("runtime").also { it.isAccessible = true }.set(null, null)
    }

    @Test fun `same owner launch replays its crash once with the original id`() {
        DeliveryHttpServer().use { server ->
            start(keyA, server.url)
            val id = record(); close()
            val client = start(keyA, server.url)
            client.flushBlocking()
            val events = server.requests.flatMap { JSONObject(it).getJSONArray("events").objects() }
            assertEquals(listOf("corlex.crash"), events.map { it.getString("name") })
            assertEquals(id, events.single().getString("event_id"))
            client.flushBlocking()
            assertEquals(1, server.requests.size)
        }
    }
    @Test fun `another key cannot replay the old crash but its owner can recover valid history`() {
        DeliveryHttpServer().use { server ->
            start(keyA, server.url)
            val id = record(); close()
            start(keyB, server.url).flushBlocking()
            assertTrue(server.requests.isEmpty()); close()
            start(keyA, server.url).flushBlocking()
            val events = server.requests.flatMap { JSONObject(it).getJSONArray("events").objects() }
            assertEquals(listOf(id), events.map { it.getString("event_id") })
        }
    }    @Test fun `public message opt in persists details but a later private launch redacts replay`() {
        DeliveryHttpServer().use { server ->
            start(keyA, server.url, details = true)
            record()
            val file = File(app.noBackupFilesDir, "corlex").walkTopDown()
                .filter { it.isFile && it.name.startsWith("crash-") && it.extension == "json" }.single()
            assertTrue(file.readText().contains("synthetic-details")); close()
            start(keyA, server.url).flushBlocking()
            val event = JSONObject(server.requests.single()).getJSONArray("events").getJSONObject(0)
            assertEquals(CrashPrivacy.MESSAGE, event.getJSONObject("properties").getString("message"))
            assertFalse(event.toString().contains("synthetic-details"))
        }
    }

}
