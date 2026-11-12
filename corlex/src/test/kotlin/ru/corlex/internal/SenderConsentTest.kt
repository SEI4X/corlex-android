package ru.corlex.internal

import org.junit.Assert.*
import org.junit.Test

class SenderConsentTest {
    @Test fun `a stale rejection cannot clear or halt a later consent queue`() {
        val queue = EventQueue(null)
        queue.push(event(uuid(1)))
        var epoch = 0L
        val config = SenderConfig("https://example.test", "fake-key", 20).apply { this.epoch = { epoch } }
        var requests = 0
        val transport = object : Transport {
            override fun postBatch(endpoint: String, key: String, body: String): HttpResult {
                requests++
                epoch++
                queue.clear()
                queue.push(event(uuid(2)))
                return HttpResult(401, null, ByteArray(0))
            }
            override fun getConfig(endpoint: String, key: String, etag: String?) = error("unused")
        }
        val sender = Sender(queue, transport, config) { }
        assertEquals(DrainResult.Done, sender.drain())
        assertFalse(sender.rejected)
        assertEquals(1, requests)
        assertEquals(uuid(2), queue.peek(1).single().getString("event_id"))
    }

    @Test fun `withdrawn consent blocks draining an already captured queue`() {
        val queue = EventQueue(null)
        queue.push(event(uuid(1)))
        val transport = FakeTransport()
        val config = SenderConfig("https://example.test", "fake-key", 20).apply { allowed = { false } }
        assertEquals(DrainResult.Done, Sender(queue, transport, config) { }.drain())
        assertTrue(transport.batches.isEmpty())
        assertEquals(1, queue.size())
    }
}
