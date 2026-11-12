package ru.corlex.internal

import org.junit.Assert.*
import org.junit.Test
import ru.corlex.CorlexOptions
import java.io.IOException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

class DiagnosticPrivacyTest {
    @Test fun `SDK diagnostics never reflect rejected names or network exception details`() {
        val messages = ArrayList<String>()
        val transport = FakeTransport().apply { responses.add(IOException("synthetic-private-network-value")) }
        val executor = ScheduledThreadPoolExecutor(1)
        val deps = ClientDeps(MemoryStore(), null, transport, { testDevice }, executor, directExecutor, {}, messages::add)
        val client = CorlexClient("ck_test_key", CorlexOptions(remoteConfig = false, debug = true, flushIntervalMs = 60_000), deps)
        try {
            client.start()
            repeat(20) { client.track("synthetic-private invalid $it") }
            client.track("synthetic_private_name", (1..100).associate { "p$it" to "x".repeat(512) })
            client.track("small")
            client.flushBlocking()
            assertFalse(messages.joinToString().contains("synthetic"))
            assertEquals(1, messages.count { it == "invalid_event_name" })
            assertTrue(messages.contains("event_too_large"))
            assertTrue(messages.contains("request_failed"))
        } finally { client.close().get(5, TimeUnit.SECONDS); client.releaseExecutors() }
    }
}
