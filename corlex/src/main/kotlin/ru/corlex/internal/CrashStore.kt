package ru.corlex.internal

import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

internal class PendingCrash(val report: JSONObject, val remove: () -> Unit)

/** Every replay receipt captures its physical scope; a later activation cannot redirect its ack. */
internal class CrashStore(
    private val directory: File,
    private val maxFiles: Int = MAX_FILES,
    private val binding: (() -> BoundStorage?)? = null,
) {
    fun write(report: JSONObject) {
        val id = report.opt("crash_id") as? String ?: return
        if (!Limits.UUID_PATTERN.matches(id)) return
        access { dir, scope ->
            val record = if (scope == null) report else JSONObject().put("owner", scope.owner)
                .put("generation", scope.generation).put("report", report)
            val bytes = record.toString().toByteArray(Charsets.UTF_8)
            if (bytes.size > MAX_BYTES) return@access
            dir.mkdirs()
            val name = "crash-$id.json"
            val tmp = File(dir, "$name.tmp")
            FileOutputStream(tmp).use { out -> out.write(bytes); out.fd.sync() }
            if (!tmp.renameTo(File(dir, name))) tmp.delete()
            files(dir).dropLast(maxFiles.coerceAtLeast(0)).forEach { it.delete() }
        }
    }

    fun pending(): List<JSONObject> = pendingRecords().map { it.report }

    fun pendingRecords(): List<PendingCrash> = access { dir, scope ->
        dir.listFiles()?.filter { it.name.startsWith("crash-") && it.name.endsWith(".json.tmp") }?.forEach { it.delete() }
        files(dir).takeLast(maxFiles.coerceAtLeast(0)).mapNotNull { file ->
            val row = readBoundedSdkFile(file, MAX_BYTES)?.let { ConfigJson.document(it) }
            val report = if (scope == null) row else row?.takeIf {
                it.optString("owner") == scope.owner && it.optString("generation") == scope.generation
            }?.optJSONObject("report")
            val id = report?.opt("crash_id") as? String
            if (id == null || !Limits.UUID_PATTERN.matches(id) || file.name != "crash-$id.json" ||
                report.opt("v") != 1 || report.optJSONArray("frames")?.length() !in 0..CrashReport.MAX_FRAMES) {
                file.delete()
                null
            } else PendingCrash(report) { guarded(scope) { file.delete() } }
        }
    } ?: emptyList()

    fun remove(crashId: String) {
        if (Limits.UUID_PATTERN.matches(crashId)) access { dir, _ -> File(dir, "crash-$crashId.json").delete() }
    }

    fun wipe() { access { dir, _ -> files(dir).forEach { it.delete() } } }

    private fun <T> access(operation: (File, BoundStorage?) -> T): T? {
        val scope = binding?.invoke()
        if (binding != null && (scope == null || !scope.persistent)) return null
        val dir = if (scope == null) directory else scope.directory?.let { File(it, "crashes") } ?: return null
        return guarded(scope) { operation(dir, scope) }
    }

    private fun <T> guarded(scope: BoundStorage?, operation: () -> T): T? =
        if (scope == null) runCatching(operation).getOrNull() else scope.guard(operation)

    private fun files(dir: File): List<File> =
        (dir.listFiles { file -> file.name.startsWith("crash-") && file.name.endsWith(".json") } ?: emptyArray())
            .sortedWith(compareBy({ it.lastModified() }, { it.name }))

    companion object { const val MAX_FILES = 20; const val MAX_BYTES = 256 * 1024 }
}
