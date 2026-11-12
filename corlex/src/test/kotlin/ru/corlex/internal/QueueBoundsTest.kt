package ru.corlex.internal

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 35])
class QueueBoundsTest {
    @Test fun `UTF8 byte cap counts non ASCII payloads in memory and on disk`() {
        val row = event(uuid(1)).put("properties", JSONObject().put("note", "я".repeat(80)))
        val single = row.toString().toByteArray(Charsets.UTF_8).size + 1
        val file = File(tempDir(), "q.jsonl")
        val queue = EventQueue(file, maxBytes = single * 3 / 2)
        queue.push(row)
        queue.push(event(uuid(2)).put("properties", row.getJSONObject("properties")))
        assertEquals(1, queue.size())
        assertEquals(uuid(2), queue.peek(1).single().getString("event_id"))
        assertTrue(file.length() <= single * 3 / 2)
        assertEquals(1, EventQueue(file, maxBytes = single * 3 / 2).size())
    }
    @Test fun `oversized files and records are rejected before hydration`() {
        val file = File(tempDir(), "q.jsonl")
        file.writeText(event(uuid(1)).put("extra", "x".repeat(200)).toString())
        assertEquals(0, EventQueue(file, maxBytes = 100).size())
        assertFalse(file.exists())
        file.writeText(event(uuid(2)).put("extra", "x".repeat(Limits.MAX_EVENT_BYTES + 1)).toString())
        assertEquals(0, EventQueue(file).size())
        assertFalse(file.exists())
    }
    @Test fun `invalid UTF8 and duplicate JSON fields never become sendable queue entries`() {
        val valid = event(uuid(1)).toString() + "\n"
        val invalidUtf8 = event(uuid(2)).put("note", "x").toString().toByteArray().apply {
            this[indexOfLast { it == 'x'.code.toByte() }] = 0xff.toByte()
        }
        val duplicate = "{\"event_id\":\"${uuid(3)}\",\"event_id\":\"${uuid(4)}\",\"name\":\"e\"}"
        val file = File(tempDir(), "q.jsonl")
        file.writeBytes(valid.toByteArray() + invalidUtf8 + "\n$duplicate\n".toByteArray())
        val queue = EventQueue(file)
        assertEquals(listOf(uuid(1)), queue.peek(10).map { it.getString("event_id") })
        assertEquals(1, file.readLines().size)
    }
    @Test fun `invalid event names and UUIDs are skipped while ordinary JSON properties stay valid`() {
        val file = File(tempDir(), "q.jsonl")
        val good = event(uuid(1)).put("properties", JSONObject().put("constructor", "ordinary-value"))
        file.writeText(listOf(good, event("not-uuid"), event(uuid(2), "Private Dynamic Name")).joinToString("\n"))
        val queued = EventQueue(file).peek(10)
        assertEquals(1, queued.size)
        assertEquals("ordinary-value", queued.single().getJSONObject("properties").getString("constructor"))
    }    @Test fun `a queue symlink never reads or deletes a host application file`() {
        val host = File(tempDir(), "host.jsonl").apply { writeText(event(uuid(1)).toString()) }
        val link = File(tempDir(), "q.jsonl")
        java.nio.file.Files.createSymbolicLink(link.toPath(), host.toPath())
        assertEquals(0, EventQueue(link).size())
        assertTrue(host.exists())
        assertEquals(event(uuid(1)).toString(), host.readText())
    }

}
