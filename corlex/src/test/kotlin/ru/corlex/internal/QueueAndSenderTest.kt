package ru.corlex.internal

import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import kotlin.random.Random

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EventQueueTest {
    @Test fun `events survive a restart in order`() {
        val file = File(tempDir(), "q.jsonl")
        val first = EventQueue(file)
        first.push(event(uuid(1)))
        first.push(event(uuid(2)))
        val second = EventQueue(file)
        assertEquals(listOf(uuid(1), uuid(2)), second.peek(10).map { it.getString("event_id") })
    }

    @Test fun `drop removes exactly the delivered batch and the file follows`() {
        val file = File(tempDir(), "q.jsonl")
        val queue = EventQueue(file)
        (1..3).forEach { queue.push(event(uuid(it))) }
        queue.drop(queue.peek(2))
        assertEquals(listOf(uuid(3)), EventQueue(file).peek(10).map { it.getString("event_id") })
        queue.drop(queue.peek(1))
        assertFalse("an empty queue leaves no file", file.exists())
    }

    @Test fun `the oldest events go first when the queue is over its size`() {
        val file = File(tempDir(), "q.jsonl")
        val queue = EventQueue(file, maxEvents = 3)
        (1..5).forEach { queue.push(event(uuid(it))) }
        assertEquals(listOf(uuid(3), uuid(4), uuid(5)), queue.peek(10).map { it.getString("event_id") })
        assertEquals(3, EventQueue(file).size())
    }

    @Test fun `the file is capped in bytes too`() {
        val file = File(tempDir(), "q.jsonl")
        val queue = EventQueue(file, maxBytes = 600)
        (1..20).forEach { queue.push(event(uuid(it), "x".repeat(40))) }
        assertTrue(file.length() <= 600)
        assertEquals(uuid(20), queue.peek(100).last().getString("event_id"))
    }

    @Test fun `without a file nothing touches the disk, and persistence can start later`() {
        val dir = tempDir()
        val file = File(dir, "q.jsonl")
        val queue = EventQueue(null)
        queue.push(event(uuid(1)))
        assertFalse(file.exists())
        queue.enablePersistence(file)
        assertEquals(1, EventQueue(file).size())
    }

    @Test fun `the queue directory is created on the first event`() {
        val file = File(tempDir(), "corlex/nested/queue.jsonl")
        EventQueue(file).push(event(uuid(1)))
        assertEquals(1, EventQueue(file).size())
    }

    @Test fun `a corrupt line is skipped and the rest is kept`() {
        val file = File(tempDir(), "q.jsonl")
        file.writeText(event(uuid(1)).toString() + "\n{not json\n" + event(uuid(2)).toString() + "\n")
        assertEquals(2, EventQueue(file).size())
    }

    @Test fun `clear removes the file`() {
        val file = File(tempDir(), "q.jsonl")
        val queue = EventQueue(file)
        queue.push(event(uuid(1)))
        queue.clear()
        assertFalse(file.exists())
        assertEquals(0, queue.size())
    }
}

class SenderTest {
    private val config = SenderConfig("https://ingest.example", "ck_test_key", batchSize = 2)

    private fun setup(vararg responses: Any, events: Int = 5): Triple<Sender, EventQueue, FakeTransport> {
        val transport = FakeTransport()
        transport.responses = ArrayDeque(responses.toList())
        val queue = EventQueue(null)
        (1..events).forEach { queue.push(event(uuid(it))) }
        return Triple(Sender(queue, transport, config) { }, queue, transport)
    }

    @Test fun `sends in batches of the configured size and empties the queue`() {
        val (sender, queue, transport) = setup()
        assertEquals(DrainResult.Done, sender.drain())
        assertEquals(listOf(2, 2, 1), transport.batches.map { it.getJSONArray("events").length() })
        assertEquals(0, queue.size())
        assertEquals("https://ingest.example", transport.batches[0].getString("_endpoint"))
        assertEquals("ck_test_key", transport.batches[0].getString("_key"))
    }

    @Test fun `a server error keeps the events and the retry sends the same ids`() {
        val (sender, queue, transport) = setup(FakeTransport().status(503), events = 2)
        val first = sender.drain()
        assertTrue(first is DrainResult.Failed)
        assertEquals(2, queue.size())
        assertEquals(DrainResult.Done, sender.drain())
        val ids = transport.batches.map { b -> b.getJSONArray("events").objects().map { it.getString("event_id") } }
        assertEquals(ids[0], ids[1])
    }

    @Test fun `a network error is a retry`() {
        val (sender, queue, _) = setup(IOException("offline"), events = 1)
        assertTrue(sender.drain() is DrainResult.Failed)
        assertEquals(1, queue.size())
    }

    @Test fun `blocked redirect retains events for retry`() {
        val (sender, queue, _) = setup(FakeTransport().status(307), events = 1)
        assertTrue(sender.drain() is DrainResult.Failed)
        assertEquals(1, queue.size())
    }

    @Test fun `429 waits at least Retry-After`() {
        val (sender, _, _) = setup(FakeTransport().status(429, "45"), events = 1)
        val result = sender.drain() as DrainResult.Failed
        assertTrue("delay ${result.delayMs}", result.delayMs >= 45_000)
    }

    @Test fun `a final 4xx drops the batch and goes on`() {
        val (sender, queue, transport) = setup(FakeTransport().status(400), events = 3)
        assertEquals(DrainResult.Done, sender.drain())
        assertEquals(0, queue.size())
        assertEquals(2, transport.batches.size)
    }

    @Test fun `413 halves the batch until a single event is dropped`() {
        val big = FakeTransport().status(413)
        val (sender, queue, transport) = setup(big, big, big, events = 2)
        assertEquals(DrainResult.Done, sender.drain())
        assertEquals(0, queue.size())
        assertEquals(listOf(2, 1, 1), transport.batches.map { it.getJSONArray("events").length() })
    }

    @Test fun `401 rejects the key, clears the queue and stops for good`() {
        val (sender, queue, transport) = setup(FakeTransport().status(401))
        sender.drain()
        assertTrue(sender.rejected)
        assertEquals(0, queue.size())
        queue.push(event(uuid(99)))
        sender.drain()
        assertEquals(1, transport.batches.size)
    }
}

class BackoffTest {
    @Test fun `Retry-After takes only seconds and is capped`() {
        assertEquals(30_000L, Backoff.parseRetryAfter("30"))
        assertEquals(300_000L, Backoff.parseRetryAfter("99999"))
        assertNull(Backoff.parseRetryAfter("Wed, 21 Oct 2026 07:28:00 GMT"))
        assertNull(Backoff.parseRetryAfter(null))
    }

    @Test fun `backoff doubles with equal jitter up to a minute`() {
        val random = Random(1)
        repeat(50) {
            assertTrue(Backoff.delay(1, random) in 500..1000)
            assertTrue(Backoff.delay(3, random) in 2000..4000)
            assertTrue(Backoff.delay(30, random) in 30_000..60_000)
        }
    }
}
