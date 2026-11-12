package ru.corlex.internal

import ru.corlex.CorlexOptions
import ru.corlex.Consent
import org.json.JSONObject
import java.util.concurrent.Future
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * One SDK instance: events, consent, the queue, delivery, identity and Remote Config. Public calls
 * only snapshot their input (time, properties) and hand over to one executor thread, so the app's
 * main thread never touches the disk or the network.
 */
internal class CorlexClient(private val key: String, private val options: CorlexOptions, private val deps: ClientDeps) {
    @Volatile private var consent = Consent.PENDING
    private val lifetime = ClientLifetime(deps)
    private val storage = ClientStorage(deps, key to options) { state ->
        if (state != Consent.GRANTED && consent != Consent.DENIED) consent = state
        lifetime.epoch.incrementAndGet()
        deps.transport.pause()
    }
    private var deliveryOnly = false
    private var queue: EventQueue? = null
    @Volatile private var identity: Identity? = null
    private var sender: Sender? = null
    private var timer: ScheduledFuture<*>? = null
    private var backingOff = false
    private var warnedName = false
    private val exposed = HashSet<String>()
    val remote: RemoteConfig? = createRemote()

    private val endpoint = options.endpoint.trimEnd('/')
    private val batchSize = options.batchSize.coerceIn(1, Limits.MAX_BATCH_EVENTS)

    fun consent(): Consent = if (consent == Consent.DENIED) consent else storage.cachedConsent()?.let { if (it == Consent.GRANTED) consent else it } ?: consent

    /** Protected production initialization is queued rather than blocking the application's thread. */
    fun start() {
        val barrier = deps.startupBarrier
        if (barrier == null && deps.bindingDisk == null) {
            initialize()
            if (consent != Consent.DENIED) deps.executor.execute { if (!lifetime.closed) openQueue() }
        } else {
            deps.executor.execute {
                try { barrier?.get() }
                catch (_: Exception) { lifetime.failStartup(); consent = Consent.DENIED; return@execute }
                if (lifetime.closed) return@execute
                initialize()
                if (consent != Consent.DENIED) openQueue()
            }
        }
    }

    @Synchronized private fun initialize() {
        val stored = storage.initialize()
        consent = if (lifetime.desiredChoice == Consent.DENIED) Consent.DENIED else stored
        lifetime.initialized = true
        if (consent != Consent.GRANTED) deps.transport.pause()
        if (consent != Consent.DENIED) prepare()
        lifetime.initializedReady.countDown()
    }

    private fun prepare() {
        val granted = consent == Consent.GRANTED
        identity = Identity(storage.store, granted)
        remote?.bindStore(storage.store)
        if (granted && options.remoteConfig) remote?.start()
    }

    private fun openQueue() {
        val granted = consent == Consent.GRANTED
        val q = EventQueue(if (granted) storage.queueFile else null, binding = storage.scope)
        queue = q
        val config = SenderConfig(endpoint, key, batchSize).apply {
            epoch = { this@CorlexClient.lifetime.epoch.get() }
            allowed = { !lifetime.closed && consent == Consent.GRANTED && storage.cachedAllowed() }
        }
        sender = Sender(q, storage.transport, config, deps.log)
        if (granted && !deliveryOnly) {
            storage.save()
            schedule()
        }
    }

    private fun createRemote() = RemoteConfig(
        RemoteConfigOptions(
            store = storage.store,
            transport = storage.transport,
            endpoint = options.configEndpoint.trimEnd('/'),
            key = key,
            platform = "android",
            live = options.liveConfig,
            idOf = { identity?.let { it.userId ?: it.anonymousId } },
            expose = ::expose,
            log = deps.log,
        ).apply {
            epoch = { lifetime.epoch.get() }
            allowed = { !lifetime.closed && consent == Consent.GRANTED && storage.cachedAllowed() }
        },
        deps.configExecutor,
    )

    fun track(name: String, properties: Map<String, Any?>? = null) = capture(name, properties, CaptureMeta())

    /** For SDK internals: [track] for an event that already happened, with its own time and id. */
    fun capture(name: String, properties: Map<String, Any?>?, meta: CaptureMeta) {
        if (lifetime.closed || (lifetime.initialized && !storage.active()) || consent() == Consent.DENIED) return
        val clean = Props.sanitize(properties)
        val timestamp = meta.timestamp ?: Timestamps.format(System.currentTimeMillis())
        deps.executor.execute {
            try {
                record(name, clean, timestamp, meta)
            } finally {
                meta.onRecorded?.invoke()
            }
        }
    }

    private fun record(name: String, props: JSONObject, timestamp: String, meta: CaptureMeta) {
        val q = queue ?: return
        val id = identity ?: return
        if (!storage.active() || consent() == Consent.DENIED) return
        if (!Limits.NAME_PATTERN.matches(name)) return rejectName()
        val event = buildEvent(name, props, timestamp, meta, id)
        if (q.has(event.getString("event_id"))) return
        if (event.toString().toByteArray().size > Limits.MAX_EVENT_BYTES) {
            deps.log("event_too_large")
            return
        }
        q.push(event)
        if (consent == Consent.GRANTED) schedule()
    }

    private fun rejectName() {
        if (warnedName) return
        warnedName = true; deps.log("invalid_event_name")
    }

    private fun buildEvent(name: String, props: JSONObject, timestamp: String, meta: CaptureMeta, id: Identity): JSONObject {
        val device = deps.device()
        val build = meta.build ?: device.build
        if (build != null && !props.has("build") && props.length() < Limits.MAX_PROPERTIES) props.put("build", build)
        val eventId = meta.eventId?.takeIf { Limits.UUID_PATTERN.matches(it) } ?: newUuid()
        val event = JSONObject()
            .put("event_id", eventId)
            .put("name", name)
            .put("timestamp", timestamp)
            .put("anonymous_id", id.anonymousId)
        id.userId?.let { event.put("user_id", it) }
        return event.put("properties", props).put("context", device.toContext(meta.appVersion))
    }

    fun identify(userId: String, traits: Map<String, Any?>? = null) {
        if (consent == Consent.DENIED || userId.isEmpty()) return
        deps.executor.execute { identity?.setUser(userId) }
        track(Events.IDENTIFY, traits)
    }

    fun setUserProperties(properties: Map<String, Any?>) = track(Events.USER_PROPS, properties)

    fun reset() {
        deps.executor.execute {
            synchronized(exposed) { exposed.clear() }
            identity?.reset()
        }
    }

    private fun expose(experiment: String, variant: String) {
        val id = identity?.let { it.userId ?: it.anonymousId } ?: return
        val first = synchronized(exposed) { exposed.add("$experiment|$id") }
        if (first) track(Events.EXPOSURE, mapOf("experiment" to experiment, "variant" to variant))
    }

    /** Sent when a batch is full, else on the interval. Runs on the executor. */
    private fun schedule() {
        val q = queue ?: return
        if (consent != Consent.GRANTED || q.size() == 0 || backingOff || sender?.rejected == true) return
        if (q.size() >= batchSize) {
            drainNow()
        } else if (timer?.isDone != false) {
            timer = deps.executor.schedule({ drainNow() }, options.flushIntervalMs, TimeUnit.MILLISECONDS)
        }
    }

    private fun drainNow() {
        timer?.cancel(false)
        timer = null
        backingOff = false
        if (lifetime.closed || consent != Consent.GRANTED) return
        val result = sender?.drain() ?: return
        if (result is DrainResult.Failed && !deliveryOnly && !lifetime.closed && consent == Consent.GRANTED && storage.allowed()) {
            backingOff = true
            timer = deps.executor.schedule({ drainNow() }, result.delayMs, TimeUnit.MILLISECONDS)
            deps.scheduleBackground(result.delayMs)
        }
    }

    /** Sends everything queued, on the executor, and waits. Not for the main thread. Returns whether the queue is empty. */
    fun flushBlocking(): Boolean {
        if (lifetime.closed) return true
        return deps.executor.submit<Boolean> { drainNow(); queue?.size() == 0 }.get()
    }

    /** Runs [block] on the SDK's thread, after everything posted before it. */
    fun post(block: () -> Unit) = deps.executor.execute(block)

    /** Sends everything queued, without waiting. */
    fun flush() {
        deps.executor.execute { drainNow() }
    }

    /** The app goes to the background: send now, and hand the rest to WorkManager. */
    fun onBackground() {
        deps.executor.execute {
            drainNow()
            if ((queue?.size() ?: 0) > 0 && consent == Consent.GRANTED) deps.scheduleBackground(0)
        }
    }


    @Synchronized fun optIn() {
        if (lifetime.closed || lifetime.startupFailed) return
        lifetime.desiredChoice = Consent.GRANTED
        if (lifetime.initialized && consent == Consent.GRANTED) return
        if (lifetime.initialized) consent = if (deps.bindingDisk == null) Consent.GRANTED else Consent.PENDING
        deps.executor.execute {
            if (lifetime.closed || lifetime.startupFailed || lifetime.desiredChoice != Consent.GRANTED) return@execute
            val preserve = storage.activate()
            consent = storage.scope?.consent() ?: Consent.GRANTED
            if (consent != Consent.GRANTED) return@execute
            deps.transport.resume()
            storage.store.set(CONSENT_KEY, "granted")
            if (!preserve) { remote?.stop(wipe = false); queue = null; identity = null }
            val q = queue
            if (q == null || identity == null) {
                prepare()
                openQueue()
                return@execute
            }
            identity?.rebind(storage.store)
            remote?.bindStore(storage.store)
            storage.queueFile?.let { q.enablePersistence(it, storage.scope) }
            storage.save()
            if (options.remoteConfig) remote?.start()
            schedule()
        }
    }

    /** Stops collecting and removes everything the SDK stored on this device, the saved config too. */
    @Synchronized fun optOut() {
        lifetime.desiredChoice = Consent.DENIED
        consent = Consent.DENIED
        val eraseBound = storage.denial()
        lifetime.deny {
            eraseBound()
            deps.store.set(CONSENT_KEY, "denied")
            listOf(KEY_SETTING, ENDPOINT_SETTING, BATCH_SETTING, INSECURE_SETTING,
                Identity.ANON_KEY, Identity.USER_KEY, RemoteConfig.CONFIG_KEY).forEach(deps.store::remove)
            remote?.stop(wipe = true)
            timer?.cancel(false)
            queue?.clear()
            if (deps.bindingDisk == null) deps.queueFile?.delete()
            identity?.wipe()
            identity = Identity(deps.store, false)
            synchronized(exposed) { exposed.clear() }
        }
    }

    /** A worker only opens the saved queue: it never creates identity/config/timer state. */
    fun startDeliveryOnly() {
        deliveryOnly = true
        consent = storage.initialize()
        if (consent != Consent.GRANTED) { deps.transport.pause(); return }
        deps.executor.execute { if (!lifetime.closed) openQueue() }
    }

    /** Invalidates requests immediately; the returned barrier protects the next file owner. */
    fun close(): Future<Unit> {
        storage.close()
        return lifetime.close {
            timer?.cancel(false)
            remote?.stop(wipe = false)
            queue = null
            sender = null
        }
    }

    fun crashScope(): BoundStorage? = storage.scope
    fun awaitConfigReady(timeoutMs: Long): Boolean = lifetime.awaitConfig(timeoutMs, remote)
    fun releaseExecutors() = lifetime.release()

    companion object {
        const val CONSENT_KEY = "corlex_consent"
        const val KEY_SETTING = "corlex_key"
        const val ENDPOINT_SETTING = "corlex_endpoint"
        const val BATCH_SETTING = "corlex_batch"
        const val INSECURE_SETTING = "corlex_allow_insecure_http"
    }
}
