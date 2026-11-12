package ru.corlex.internal

import org.json.JSONObject
import ru.corlex.Consent
import ru.corlex.CorlexOptions
import java.io.Closeable
import java.io.File

/** Runtime selection never changes the owner captured by an older identity/config/queue. */
internal class ClientStorage(
    private val deps: ClientDeps,
    private val init: Pair<String, CorlexOptions>,
    private val cancelled: (Consent) -> Unit,
) {
    @Volatile var scope: BoundStorage? = null
        private set
    private var watch: Closeable? = null
    val store: KeyValueStore get() = scope ?: deps.store
    val queueFile: File? get() = if (deps.bindingDisk == null) deps.queueFile
        else scope?.directory?.let { File(it, "queue.jsonl") }

    fun initialize(): Consent {
        val disk = deps.bindingDisk ?: return when (deps.store.get(CorlexClient.CONSENT_KEY)) {
            "granted" -> Consent.GRANTED
            "denied" -> Consent.DENIED
            else -> init.second.consent
        }
        val owner = bindingIdentity(init.first, init.second) ?: return Consent.DENIED
        select(disk.open(owner, init.second.consent))
        return scope?.consent() ?: Consent.PENDING
    }

    fun activate(): Boolean {
        val disk = deps.bindingDisk ?: return true
        val owner = bindingIdentity(init.first, init.second) ?: return false
        val previous = scope
        val pending = previous?.persistent == true && previous.consent() == Consent.PENDING && previous.choice == Consent.PENDING
        watch?.close()
        select(disk.open(owner, Consent.GRANTED, explicit = true))
        return pending && previous?.generation == scope?.generation
    }

    private fun select(next: BoundStorage) {
        watch?.close()
        scope = next
        watch = next.disk.watch(next) { if (scope === next) cancelled(next.consent()) }
    }

    fun allowed(): Boolean = deps.bindingDisk == null || scope?.granted() == true
    fun active(): Boolean = deps.bindingDisk == null || scope?.let { it.disk.isCurrent(it) } == true
    fun cachedConsent(): Consent? = if (deps.bindingDisk == null) null else scope?.let { it.disk.cached(it) } ?: Consent.PENDING
    fun cachedAllowed(): Boolean = deps.bindingDisk == null || scope?.let { it.disk.cached(it) == Consent.GRANTED } == true
    val transport: Transport = object : Transport {
        override fun postBatch(endpoint: String, key: String, body: String): HttpResult = request {
            deps.transport.postBatch(endpoint, key, body)
        }
        override fun getConfig(endpoint: String, key: String, etag: String?): HttpResult = request {
            deps.transport.getConfig(endpoint, key, etag)
        }
        override fun pause() = deps.transport.pause()
        override fun resume() = deps.transport.resume()
        private fun request(operation: () -> HttpResult): HttpResult {
            if (!allowed()) throw java.io.IOException("SDK request interrupted")
            val result = operation()
            if (!allowed()) throw java.io.IOException("SDK request interrupted")
            return result
        }
    }
    fun close() { watch?.close(); watch = null }
    fun denial(): () -> Unit {
        val disk = deps.bindingDisk ?: return {}
        val expected = disk.fenceDenied()
        return { disk.deny(expected) }
    }

    fun save() {
        val (key, options) = init
        val bound = scope
        if (bound != null) {
            if (!bound.persistent) return
            bound.guard {
                bound.disk.write(File(bound.disk.root, "auth.json"), JSONObject()
                    .put("owner", bound.owner).put("generation", bound.generation).put("key", key)
                    .put("endpoint", options.endpoint).put("configEndpoint", options.configEndpoint)
                    .put("batchSize", options.batchSize.coerceIn(1, Limits.MAX_BATCH_EVENTS)).put("allowInsecureHttp", options.allowInsecureHttp))
            }
            return
        }
        if (deps.bindingDisk != null) return
        store.set(CorlexClient.CONSENT_KEY, "granted")
        store.set(CorlexClient.KEY_SETTING, key)
        store.set(CorlexClient.ENDPOINT_SETTING, options.endpoint.trimEnd('/'))
        store.set(CorlexClient.BATCH_SETTING, options.batchSize.coerceIn(1, Limits.MAX_BATCH_EVENTS).toString())
        store.set(CorlexClient.INSECURE_SETTING, options.allowInsecureHttp.toString())
    }
}
