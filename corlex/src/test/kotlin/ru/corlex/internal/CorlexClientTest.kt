package ru.corlex.internal

import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

import ru.corlex.CorlexOptions
import ru.corlex.Consent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CorlexClientTest {
    private val clients = ArrayList<TestClient>()

    private fun client(
        options: CorlexOptions = CorlexOptions(flushIntervalMs = 60_000),
        transport: FakeTransport = FakeTransport(),
        store: KeyValueStore = MemoryStore(),
        dir: java.io.File = tempDir(),
    ) = TestClient(options, transport, store, dir).also { clients.add(it) }

    @After fun tearDown() = clients.forEach { it.close() }

    @Test fun `every event carries platform, version, build, os, device and locale`() {
        val c = client()
        c.client.track("purchase", mapOf("amount" to 12.5, "plan" to "pro"))
        assertTrue(c.client.flushBlocking())
        val event = c.transport.sentEvents().single()
        assertEquals("purchase", event.getString("name"))
        assertTrue(event.getString("anonymous_id").startsWith("a_"))
        assertFalse(event.has("user_id"))
        assertTrue(event.getString("timestamp").matches(Regex("\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\d\\.\\d{3}Z")))
        assertTrue(Limits.UUID_PATTERN.matches(event.getString("event_id")))
        val props = event.getJSONObject("properties")
        assertEquals(12.5, props.getDouble("amount"), 0.0)
        assertEquals("42", props.getString("build"))
        val context = event.getJSONObject("context")
        assertEquals("android", context.getString("platform"))
        assertEquals("1.2.3", context.getString("app_version"))
        assertEquals("Android", context.getString("os"))
        assertEquals("14", context.getString("os_version"))
        assertEquals("Pixel 8", context.getString("device_model"))
        assertEquals("en-US", context.getString("locale"))
        assertEquals("corlex-android", context.getJSONObject("sdk").getString("name"))
    }

    @Test fun `the project key goes in the request, not in an event`() {
        val c = client()
        c.client.track("a")
        c.client.flushBlocking()
        assertEquals("ck_test_key", c.transport.batches.single().getString("_key"))
        assertFalse(c.transport.sentEvents().single().toString().contains("ck_test_key"))
    }

    @Test fun `identify ties later events to the user and reset starts a new person`() {
        val c = client()
        c.client.identify("u_42", mapOf("plan" to "pro"))
        c.client.track("after")
        c.client.flushBlocking()
        val events = c.transport.sentEvents()
        assertEquals(listOf("corlex.identify", "after"), events.map { it.getString("name") })
        assertEquals("pro", events[0].getJSONObject("properties").getString("plan"))
        assertEquals("u_42", events[1].getString("user_id"))
        val anonymous = events[1].getString("anonymous_id")

        c.client.reset()
        c.client.track("later")
        c.client.flushBlocking()
        val later = c.transport.sentEvents().last()
        assertFalse(later.has("user_id"))
        assertTrue(later.getString("anonymous_id") != anonymous)
    }

    @Test fun `the user id and anonymous id survive a restart`() {
        val store = MemoryStore()
        val first = client(store = store)
        first.client.identify("u_1")
        first.settle()
        val second = client(store = store, dir = tempDir())
        second.client.track("x")
        second.client.flushBlocking()
        val event = second.transport.sentEvents().single()
        assertEquals("u_1", event.getString("user_id"))
    }

    @Test fun `names outside the protocol are dropped`() {
        val c = client()
        c.client.track("Has Capitals")
        c.client.track("")
        c.client.track("ok_name")
        c.client.flushBlocking()
        assertEquals(listOf("ok_name"), c.transport.sentEvents().map { it.getString("name") })
    }

    @Test fun `an event over 32 KiB is dropped`() {
        val c = client()
        c.client.track("big", (1..100).associate { "k$it" to "x".repeat(1000) })
        c.client.track("small")
        c.client.flushBlocking()
        assertEquals(listOf("small"), c.transport.sentEvents().map { it.getString("name") })
    }

    @Test fun `a full batch is sent at once, a partial one waits for the interval`() {
        val c = client(CorlexOptions(batchSize = 2, flushIntervalMs = 60_000))
        c.client.track("a")
        c.settle()
        assertEquals(0, c.transport.batches.size)
        c.client.track("b")
        c.settle()
        assertEquals(1, c.transport.batches.size)
    }

    @Test fun `a partial batch goes out when the interval passes`() {
        val c = client(CorlexOptions(flushIntervalMs = 50))
        c.client.track("a")
        val deadline = System.currentTimeMillis() + 3000
        while (c.transport.batches.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertEquals(1, c.transport.batches.size)
    }

    @Test fun `events queued before a crash of the process are delivered by the next launch`() {
        val dir = tempDir()
        val store = MemoryStore()
        val offline = FakeTransport().also { it.responses = ArrayDeque(List(5) { _ -> java.io.IOException("offline") }) }
        val first = client(transport = offline, store = store, dir = dir)
        first.client.track("kept")
        first.client.flushBlocking()
        assertEquals(1, first.queued().size)
        first.close()

        val second = client(store = store, dir = dir)
        assertTrue(second.client.flushBlocking())
        assertEquals(listOf("kept"), second.transport.sentEvents().map { it.getString("name") })
        assertTrue(second.queued().isEmpty())
    }

    @Test fun `pending consent sends nothing and writes nothing until optIn`() {
        val store = MemoryStore()
        val c = client(CorlexOptions(consent = Consent.PENDING, flushIntervalMs = 10), store = store)
        c.client.track("held_1")
        c.client.track("held_2")
        c.client.flush()
        c.settle()
        Thread.sleep(50)
        assertEquals(0, c.transport.batches.size)
        assertEquals(0, c.transport.configRequests.size)
        assertFalse("no queue file", c.queueFile.exists())
        assertNull("no anonymous id on disk", store.get(Identity.ANON_KEY))
        assertEquals(Consent.PENDING, c.client.consent())

        c.client.optIn()
        assertTrue(c.client.flushBlocking())
        assertEquals(listOf("held_1", "held_2"), c.transport.sentEvents().map { it.getString("name") })
        assertEquals("granted", store.get(CorlexClient.CONSENT_KEY))
        assertTrue(store.get(Identity.ANON_KEY) != null)
    }

    @Test fun `opt-out clears the queue and the ids, and the choice survives a restart`() {
        val store = MemoryStore()
        val dir = tempDir()
        val offline = FakeTransport().also { it.responses = ArrayDeque(List(5) { _ -> java.io.IOException("offline") }) }
        val c = client(transport = offline, store = store, dir = dir)
        c.client.identify("u_1")
        c.client.track("queued")
        c.client.flushBlocking()
        assertTrue(c.queued().isNotEmpty())

        c.client.optOut()
        c.client.track("after_opt_out")
        c.settle()
        assertTrue(c.queued().isEmpty())
        assertNull(store.get(Identity.ANON_KEY))
        assertNull(store.get(Identity.USER_KEY))
        assertNull(store.get(CorlexClient.KEY_SETTING))
        assertEquals(Consent.DENIED, c.client.consent())

        val next = client(store = store, dir = dir)
        next.client.track("silent")
        next.client.flushBlocking()
        assertEquals(0, next.transport.batches.size)
        assertEquals(Consent.DENIED, next.client.consent())
    }
}
