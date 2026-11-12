package ru.corlex.internal

import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

internal class RemoteConfigOptions(
    @Volatile var store: KeyValueStore,
    val transport: Transport,
    val endpoint: String,
    val key: String,
    /** What a platform override in the config is matched with. */
    val platform: String,
    /** Apply a downloaded document at once instead of at the next launch. */
    val live: Boolean,
    /** The user id after identify(), otherwise the anonymous id: what the rollout buckets on. */
    val idOf: () -> String?,
    /** A variant was shown: the client sends the exposure (once per session). */
    val expose: (experiment: String, variant: String) -> Unit,
    val log: (String) -> Unit,
) {
    var epoch: () -> Long = { 0L }
    var allowed: () -> Boolean = { true }
}

internal fun interface ConfigListener {
    fun onUpdate(version: Int, applied: Boolean)
}

/**
 * The project's published config on this device (SPEC section 10). The document is downloaded once
 * per launch (a conditional GET, usually a 304), kept in the store and evaluated HERE with the same
 * stable bucketing as the server, so no request names a person. A value must not flip while the
 * app is running: a new document is applied at once only if nothing was read yet in this launch
 * (or `live` is on); otherwise it waits for the next launch.
 */
internal class RemoteConfig(private val options: RemoteConfigOptions, private val executor: Executor) {
    @Volatile private var active: ConfigDoc? = null
    @Volatile private var etag: String? = null
    @Volatile private var started = false
    @Volatile private var handedOut = false
    @Volatile private var firstDownload = CountDownLatch(1)
    private var generation = 0L
    private var request = 0L
    private inner class Download(val generation: Long, val request: Long, val epoch: Long) { val ready = firstDownload }
    private val listeners = CopyOnWriteArrayList<ConfigListener>()

    /** Reads the saved document and downloads the current one. Needs consent: the caller checks. */
    fun start() {
        val token = synchronized(this) {
            if (started || !options.allowed()) return
            generation++
            firstDownload = CountDownLatch(1)
            handedOut = false
            active = null
            etag = null
            started = true
            load()
            nextRequest()
        }
        executor.execute { download(token) }
    }

    /** Stops downloads; old request generations cannot mutate a later grant's cache. */
    @Synchronized fun stop(wipe: Boolean) {
        started = false
        generation++
        if (wipe) {
            options.store.remove(CONFIG_KEY)
            active = null
            etag = null
        }
        firstDownload.countDown()
    }

    @Synchronized fun bindStore(store: KeyValueStore) {
        stop(wipe = false)
        options.store = store
        active = null
        etag = null
    }

    fun refresh() {
        val token = synchronized(this) {
            if (!started || !options.allowed()) return
            nextRequest()
        }
        executor.execute { download(token) }
    }

    private fun nextRequest(): Download = Download(generation, ++request, options.epoch())
    private fun current(token: Download): Boolean = started && token.generation == generation &&
        token.request == request && token.epoch == options.epoch() && options.allowed()

    /** Wait only for this grant's current request; a previous grant cannot release this latch. */
    fun awaitReady(timeoutMs: Long): Boolean {
        val ready = synchronized(this) { if (!started || !options.allowed()) return true; firstDownload }
        return ready.await(timeoutMs, TimeUnit.MILLISECONDS)
    }

    fun addListener(listener: ConfigListener) = listeners.add(listener)
    fun removeListener(listener: ConfigListener) = listeners.remove(listener)

    /** The value for [key] if the person gets one of the same type as [fallback], else [fallback]. */
    fun get(key: String, fallback: Any): Any {
        if (!options.allowed()) return fallback
        handedOut = true
        val doc = active
        val id = options.idOf()
        if (doc == null || id == null) return fallback
        val linked = doc.experiments.firstOrNull { it.param == key }
        val assigned = linked?.let { ConfigEval.assign(it, id) }
        if (linked != null && assigned != null) {
            // A parameter under test gives the person their variant's value, and that is the exposure.
            if (ConfigEval.kindOf(assigned.value) != ConfigEval.kindOf(fallback)) return fallback
            options.expose(linked.key, assigned.variant)
            return assigned.value!!
        }
        val param = doc.params.firstOrNull { it.key == key } ?: return fallback
        val resolved = ConfigEval.resolve(param, id, options.platform) ?: return fallback
        val value = resolved.value
        return if (value != null && ConfigEval.kindOf(value) == ConfigEval.kindOf(fallback)) value else fallback
    }

    /** Every parameter that applies to this person, by key. */
    fun getAll(): Map<String, Any?> {
        if (!options.allowed()) return emptyMap()
        handedOut = true
        val doc = active
        val id = options.idOf()
        if (doc == null || id == null) return emptyMap()
        val out = LinkedHashMap<String, Any?>()
        for (param in doc.params) ConfigEval.resolve(param, id, options.platform)?.let { out[param.key] = it.value }
        // Variants of linked parameters are listed, but listing is not an exposure.
        for (experiment in doc.experiments) {
            val paramKey = experiment.param ?: continue
            ConfigEval.assign(experiment, id)?.let { out[paramKey] = it.value }
        }
        return out
    }

    /** The variant of a running experiment for this person, or null. Reading it IS the exposure. */
    fun experiment(key: String): Assignment? {
        if (!options.allowed()) return null
        handedOut = true
        val experiment = active?.experiments?.firstOrNull { it.key == key }
        val id = options.idOf()
        if (experiment == null || id == null) return null
        val assigned = ConfigEval.assign(experiment, id) ?: return null
        options.expose(experiment.key, assigned.variant)
        return assigned
    }

    private fun load() {
        val raw = options.store.get(CONFIG_KEY) ?: return
        val saved = ConfigJson.objectOf(raw, cache = true)
        val doc = (saved?.opt("doc") as? JSONObject)?.let(ConfigDoc::parse)
        val validator = saved?.opt("etag")
        if (doc == null || !ConfigSchema.etag(validator) || validator != doc.etag) {
            options.store.remove(CONFIG_KEY)
            return
        }
        if (options.allowed()) { active = doc; etag = doc.etag }
    }

    private fun download(token: Download) {
        try {
            if (!synchronized(this) { current(token) }) return
            val response = options.transport.getConfig(options.endpoint, options.key, etag)
            if (!synchronized(this) { current(token) }) return
            if (response.status != 200) return
            val raw = ConfigJson.document(response.body) ?: return
            val doc = ConfigDoc.parse(raw) ?: return
            apply(token, doc, raw)
        } catch (_: Exception) {
            options.log("config: download or document validation failed")
        } finally {
            synchronized(this) { if (token.generation == generation && token.request == request) token.ready.countDown() }
        }
    }

    private fun apply(token: Download, doc: ConfigDoc, raw: JSONObject) {
        val applied = synchronized(this) {
            if (!current(token)) return
            options.store.set(CONFIG_KEY, JSONObject().put("etag", doc.etag).put("doc", raw).toString())
            etag = doc.etag
            val applies = options.live || !handedOut || active == null
            if (applies) active = doc
            applies
        }
        for (listener in listeners) {
            if (!synchronized(this) { current(token) }) return
            runCatching { listener.onUpdate(doc.version, applied) }.onFailure { options.log("config: listener failed") }
        }
    }

    companion object {
        /** Where the last document lives between launches. It holds no personal data. */
        const val CONFIG_KEY = "corlex_cfg"
    }
}
