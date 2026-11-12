package ru.corlex.internal

import org.json.JSONObject
import ru.corlex.Consent
import java.io.File

internal class BoundStorage(
    val disk: BindingDisk,
    val owner: String,
    val generation: String,
    val choice: Consent,
    val directory: File?,
    val persistent: Boolean,
) : KeyValueStore {
    private val memory = MemoryStore()
    private val file = directory?.let { File(it, "state.json") }

    fun consent(): Consent = disk.current(this)
    fun granted(): Boolean = consent() == Consent.GRANTED
    fun <T> guard(operation: () -> T): T? = disk.granted(this, operation)

    override fun get(key: String): String? = guard {
        if (file == null) memory.get(key) else values().opt(key) as? String
    }
    override fun set(key: String, value: String) {
        guard {
            if (file == null) memory.set(key, value)
            else save(values().put(key, value))
        }
    }
    override fun remove(key: String) {
        guard {
            if (file == null) memory.remove(key)
            else save(values().apply { remove(key) })
        }
    }

    private fun values(): JSONObject {
        val row = file?.let { disk.read(it, MAX_STATE_BYTES) } ?: return JSONObject()
        if (row.optString("owner") != owner || row.optString("generation") != generation) return JSONObject()
        return row.opt("values") as? JSONObject ?: JSONObject()
    }
    private fun save(values: JSONObject) {
        val row = JSONObject().put("owner", owner).put("generation", generation).put("values", values)
        if (row.toString().toByteArray(Charsets.UTF_8).size <= MAX_STATE_BYTES) file?.let { disk.write(it, row) }
    }
    companion object { private const val MAX_STATE_BYTES = 2 * 1024 * 1024 }
}
