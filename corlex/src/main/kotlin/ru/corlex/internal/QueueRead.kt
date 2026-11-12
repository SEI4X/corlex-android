package ru.corlex.internal

import java.io.File

/** Bounds allocation before decoding, parsing or hydrating a persisted queue. */
internal object QueueRead {
    data class Record(val id: String, val line: String)

    fun records(source: File, maxBytes: Int): Sequence<Record> {
        val bytes = readBoundedSdkFile(source, maxBytes) ?: return emptySequence()
        return sequence {
            var start = 0
            for (end in 0..bytes.size) {
                if (end < bytes.size && bytes[end] != 10.toByte()) continue
                if (end > start && end - start <= Limits.MAX_EVENT_BYTES) {
                    val row = ConfigJson.document(bytes.copyOfRange(start, end), allowReserved = true)
                    val id = row?.opt("event_id") as? String
                    val name = row?.opt("name") as? String
                    if (id != null && Limits.UUID_PATTERN.matches(id) && name != null && Limits.NAME_PATTERN.matches(name))
                        yield(Record(id, row.toString()))
                }
                start = end + 1
            }
        }
    }

}
