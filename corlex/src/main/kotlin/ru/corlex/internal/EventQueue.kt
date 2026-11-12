package ru.corlex.internal

import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * Events waiting for delivery: in memory, mirrored to a file (one JSON event per line) so a crash
 * or a restart does not lose them. An event leaves the queue only after the server acknowledged it
 * (or rejected it for good). With [file] null (consent pending) nothing touches the disk.
 *
 * Size caps: [maxEvents] events and [maxBytes] bytes of file; the oldest events go first.
 */
internal class EventQueue(
    private var file: File?,
    private val maxEvents: Int = Limits.MAX_QUEUE,
    private val maxBytes: Int = Limits.MAX_QUEUE_BYTES,
    private var binding: BoundStorage? = null,
) {
    private class Item(val id: String, val line: String) { val bytes = line.toByteArray(Charsets.UTF_8).size + 1 }

    private var items = ArrayList<Item>()
    private var bytes = 0L

    init {
        file?.let { source -> guarded { load(source) } }
    }

    @Synchronized fun size(): Int = items.size

    @Synchronized fun has(eventId: String): Boolean = items.any { it.id == eventId }

    /** The first [count] events, oldest first, as JSON objects. */
    @Synchronized fun peek(count: Int): List<JSONObject> = items.take(count).map { JSONObject(it.line) }

    @Synchronized fun push(event: JSONObject) {
        val line = event.toString()
        val item = Item(event.getString("event_id"), line)
        items.add(item)
        bytes += item.bytes
        val trimmed = trim()
        guarded { if (trimmed) rewrite() else append(line) }
    }

    /** Removes exactly the events of a delivered or dropped batch. */
    @Synchronized fun drop(batch: List<JSONObject>) {
        val ids = batch.map { it.getString("event_id") }.toHashSet()
        items = ArrayList(items.filterNot { it.id in ids })
        bytes = items.sumOf { it.bytes.toLong() }
        guarded { rewrite() }
    }

    @Synchronized fun clear() {
        items.clear()
        bytes = 0
        guarded { file?.delete() }
    }

    /** Consent arrived: start mirroring the queue to [target]. */
    @Synchronized fun enablePersistence(target: File, owner: BoundStorage? = null) {
        file = target
        binding = owner
        guarded { rewrite() }
    }

    private fun guarded(operation: () -> Unit) {
        val bound = binding
        if (bound == null) operation() else bound.guard(operation)
    }

    private fun trim(): Boolean {
        var trimmed = false
        while (items.size > maxEvents || (bytes > maxBytes && items.isNotEmpty())) {
            bytes -= items.removeAt(0).bytes
            trimmed = true
        }
        return trimmed
    }

    private fun load(source: File) {
        if (!source.exists()) return
        val seen = HashSet<String>()
        for (row in QueueRead.records(source, maxBytes)) {
            if (!seen.add(row.id)) continue
            val item = Item(row.id, row.line)
            items.add(item)
            bytes += item.bytes
            trim()
        }
        // Canonical rewrite removes rejected/partial rows; disk bytes match the in-memory budget.
        rewrite()
    }

    private fun append(line: String) {
        val target = file ?: return
        try {
            target.parentFile?.mkdirs()
            FileOutputStream(target, true).use { it.write((line + "\n").toByteArray()) }
        } catch (_: java.io.IOException) {
            // Disk full or gone: the event stays in memory and is still delivered.
        }
    }

    /** Writes the whole queue to a temporary file and renames it, so a crash never leaves half a file. */
    private fun rewrite() {
        val target = file ?: return
        if (items.isEmpty()) {
            target.delete()
            return
        }
        try {
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, target.name + ".tmp")
            FileOutputStream(tmp).use { out -> items.forEach { out.write((it.line + "\n").toByteArray()) } }
            if (!tmp.renameTo(target)) tmp.delete()
        } catch (_: java.io.IOException) {
            // See append().
        }
    }
}
