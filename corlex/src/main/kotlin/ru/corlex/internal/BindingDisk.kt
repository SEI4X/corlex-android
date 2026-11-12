package ru.corlex.internal

import android.util.AtomicFile
import org.json.JSONObject
import ru.corlex.Consent
import java.io.File
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

internal data class PrivacyFence(val generation: String, val consent: Consent)

/** SDK-only, backup-excluded files. Primary-process callers also share an OS-backed file lock. */
internal class BindingDisk(val root: File, private val legacyChoice: () -> String? = { null }) {
    private val coordinator = synchronized(monitors) { monitors.getOrPut(root.absolutePath) { Coordinator() } }
    private val privacyFile = File(root, "privacy.json")

    fun open(owner: String, requested: Consent, explicit: Boolean = false): BoundStorage {
        require(Regex("^[a-f0-9]{64}$").matches(owner))
        if (requested == Consent.DENIED && !explicit) fenceDenied()
        val expected = coordinator.fence.get()
        return runCatching { locked {
            val previous = privacy()
            if (previous == null && privacyExists() && !explicit) return@locked memory(owner, Consent.PENDING, expected)
            var fence = previous ?: PrivacyFence(UUID.randomUUID().toString(), legacyConsent() ?: requested)
            if (explicit) {
                val generation = if (previous?.consent == Consent.DENIED || previous == null)
                    UUID.randomUUID().toString() else fence.generation
                fence = PrivacyFence(generation, Consent.GRANTED)
            }
            if (requested == Consent.DENIED && !explicit) {
                fence = PrivacyFence(UUID.randomUUID().toString(), Consent.DENIED)
            }
            var directory = File(File(File(root, "scopes"), owner), fence.generation)
            val choiceFile = File(directory, "choice.json")
            val saved = read(choiceFile, 1024)?.let { row ->
                if (row.optString("owner") == owner && row.optString("generation") == fence.generation)
                    parseConsent(row.optString("state")) else null
            }
            val invalidChoice = choiceFile.exists() && saved == null
            val choice = if (explicit) Consent.GRANTED else if (fence.consent != Consent.GRANTED) fence.consent
                else if (invalidChoice) Consent.PENDING else saved ?: requested
            if ((choice == Consent.PENDING && fence.consent == Consent.GRANTED) || (explicit && invalidChoice)) {
                fence = PrivacyFence(UUID.randomUUID().toString(), choice)
                directory = File(File(File(root, "scopes"), owner), fence.generation)
            }
            write(privacyFile, JSONObject().put("generation", fence.generation).put("state", fence.consent.name))
            coordinator.published.set(fence)
            if (!coordinator.fence.compareAndSet(expected, null)) return@locked memory(owner, Consent.PENDING, expected)
            announce(fence)
            wipeScopes(fence.generation)
            write(File(directory, "choice.json"), JSONObject().put("owner", owner).put("generation", fence.generation).put("state", choice.name))
            BoundStorage(this, owner, fence.generation, choice, directory, persistent = true)
        } }.getOrElse { memory(owner, if (explicit) Consent.GRANTED else Consent.PENDING, expected) }
    }

    fun fenceDenied(): PrivacyFence = PrivacyFence(UUID.randomUUID().toString(), Consent.DENIED).also {
        coordinator.fence.set(it)
        announce(it)
    }

    /** Callbacks only cancel runtime work; disk cleanup is queued separately, outside callbacks. */
    fun watch(scope: BoundStorage, cancelled: () -> Unit): java.io.Closeable {
        val token = Any()
        coordinator.watchers[token] = scope.generation to cancelled
        return java.io.Closeable { coordinator.watchers.remove(token) }
    }

    private fun announce(fence: PrivacyFence) {
        coordinator.watchers.values.toList().forEach { (generation, cancelled) ->
            if (generation != fence.generation || fence.consent != Consent.GRANTED) runCatching(cancelled)
        }
    }

    fun deny(expected: PrivacyFence = fenceDenied()) {
        runCatching { locked {
            if (coordinator.fence.get() != expected) {
                wipeScopes(privacy()?.generation)
                return@locked
            }
            write(privacyFile, JSONObject().put("generation", expected.generation).put("state", "DENIED"))
            coordinator.published.set(expected)
            wipeScopes()
        } }
    }

    fun current(scope: BoundStorage): Consent {
        coordinator.fence.get()?.let { return decision(scope, it) }
        return runCatching { locked {
            val fence = privacy() ?: return@locked if (scope.persistent) Consent.PENDING else scope.choice
            decision(scope, fence)
        } }.getOrDefault(if (scope.persistent) Consent.PENDING else scope.choice)
    }

    /** The primary-process guard makes this snapshot authoritative for synchronous cached reads. */
    fun cached(scope: BoundStorage): Consent =
        (coordinator.fence.get() ?: coordinator.published.get())?.let { decision(scope, it) } ?: Consent.PENDING

    fun isCurrent(scope: BoundStorage): Boolean =
        (coordinator.fence.get() ?: coordinator.published.get())?.let {
            it.generation == scope.generation && it.consent != Consent.DENIED
        } == true

    private fun decision(scope: BoundStorage, fence: PrivacyFence): Consent =
        if (fence.consent == Consent.DENIED) Consent.DENIED
        else if (fence.generation != scope.generation || fence.consent == Consent.PENDING) Consent.PENDING
        else scope.choice

    /** The full disk operation executes under the same fence/lock as global opt-out. */
    fun <T> granted(scope: BoundStorage, operation: () -> T): T? {
        if (!scope.persistent) {
            if (current(scope) != Consent.GRANTED) return null
            val result = operation()
            return if (current(scope) == Consent.GRANTED) result else null
        }
        return runCatching { locked {
            val fence = privacy()
            if (fence?.generation != scope.generation || fence.consent != Consent.GRANTED ||
                scope.choice != Consent.GRANTED) return@locked null
            val result = operation()
            if (privacy()?.let { it.generation == scope.generation && it.consent == Consent.GRANTED } == true) result else null
        } }.getOrNull()
    }

    fun workerAuth(): JSONObject? = runCatching { locked {
        val fence = privacy() ?: return@locked null
        if (fence.consent != Consent.GRANTED) return@locked null
        val auth = read(File(root, "auth.json"), 4096) ?: return@locked null
        if (auth.optString("generation") != fence.generation ||
            !Regex("^[a-f0-9]{64}$").matches(auth.optString("owner"))) return@locked null
        auth
    } }.getOrNull()

    internal fun read(file: File, maxBytes: Int): JSONObject? {
        if (!file.exists() && !File(file.path + ".bak").exists()) return null
        return AtomicFile(file).openRead().use { input ->
            val bytes = input.readBytesBounded(maxBytes) ?: return null
            if (maxBytes <= 4096) ConfigJson.document(bytes) else {
                val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                JSONObject(decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString())
            }
        }
    }

    internal fun write(file: File, value: JSONObject) {
        file.parentFile?.mkdirs()
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try { output.write(value.toString().toByteArray(Charsets.UTF_8)); atomic.finishWrite(output) }
        catch (failure: Exception) { atomic.failWrite(output); throw failure }
    }

    private fun privacy(): PrivacyFence? {
        coordinator.fence.get()?.let { return it }
        return readPrivacy().also { coordinator.published.set(it) }
    }
    private fun readPrivacy(): PrivacyFence? = runCatching {
        val value = read(privacyFile, 4096) ?: return null
        val generation = value.optString("generation")
        if (!Limits.UUID_PATTERN.matches(generation)) return null
        PrivacyFence(generation, parseConsent(value.optString("state")) ?: return null)
    }.getOrNull()

    private fun privacyExists() = privacyFile.exists() || File(privacyFile.path + ".bak").exists()
    private fun legacyConsent(): Consent? = when (legacyChoice()) {
        "denied" -> Consent.DENIED
        "pending" -> Consent.PENDING
        else -> null
    }
    private fun memory(owner: String, choice: Consent, expected: PrivacyFence?): BoundStorage {
        val generation = UUID.randomUUID().toString()
        val next = PrivacyFence(generation, choice)
        val canReplace = choice == Consent.GRANTED || expected?.consent != Consent.DENIED
        val applied = canReplace && coordinator.fence.compareAndSet(expected, next)
        if (applied) announce(next)
        return BoundStorage(this, owner, generation, if (applied) choice else Consent.PENDING, null, persistent = false)
    }
    private fun wipeScopes(keepGeneration: String? = null) {
        val scopes = File(root, "scopes")
        if (keepGeneration == null) scopes.deleteRecursively()
        else scopes.listFiles()?.forEach { owner ->
            owner.listFiles()?.filter { it.name != keepGeneration }?.forEach { it.deleteRecursively() }
        }
        val auth = File(root, "auth.json")
        if (keepGeneration == null || read(auth, 4096)?.optString("generation") != keepGeneration) AtomicFile(auth).delete()
        File(root, "queue.jsonl").delete()
        File(root, "crashes").deleteRecursively()
    }
    private fun <T> locked(operation: () -> T): T = synchronized(coordinator) {
        if (coordinator.fileLocked) return@synchronized operation()
        root.mkdirs()
        RandomAccessFile(File(root, "binding.lock"), "rw").use { lock -> lock.channel.lock().use {
            coordinator.fileLocked = true
            try { operation() } finally { coordinator.fileLocked = false }
        } }
    }
    private fun parseConsent(value: String): Consent? = Consent.entries.firstOrNull { it.name == value }
    private class Coordinator {
        val fence = AtomicReference<PrivacyFence?>()
        val published = AtomicReference<PrivacyFence?>()
        var fileLocked = false
        val watchers = ConcurrentHashMap<Any, Pair<String, () -> Unit>>()
    }
    companion object { private val monitors = HashMap<String, Coordinator>() }
}

private fun InputStream.readBytesBounded(limit: Int): ByteArray? {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = read(buffer, 0, minOf(buffer.size, limit + 1 - output.size()))
        if (count < 0) return output.toByteArray()
        output.write(buffer, 0, count)
        if (output.size() > limit) return null
    }
}
