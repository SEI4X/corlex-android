package ru.corlex

import ru.corlex.internal.ConfigListener

/** The variant a person got in an A/B test, and the value set for it in the Corlex screen. */
public class ExperimentAssignment(public val variant: String, public val value: Any?)

/** Called when a newer config was downloaded. `applied == false`: it is saved and takes effect at the next launch. */
public fun interface ConfigUpdateListener {
    public fun onUpdate(version: Int, applied: Boolean)
}

/**
 * Remote Config (SPEC section 10): values the project sets in the Corlex screen, evaluated on the
 * device. Every getter returns the fallback when the parameter does not exist, the rollout does not
 * reach this person, the value is of another type than the fallback, or the config is not available
 * yet (no consent, first launch before the download). They never throw and never block.
 */
public class CorlexConfig internal constructor() {
    private val remote get() = Corlex.clientOrNull()?.remote

    public fun get(key: String, fallback: Boolean): Boolean = remote?.get(key, fallback) as? Boolean ?: fallback

    public fun get(key: String, fallback: String): String = remote?.get(key, fallback) as? String ?: fallback

    public fun get(key: String, fallback: Int): Int = (remote?.get(key, fallback) as? Number)?.toInt() ?: fallback

    public fun get(key: String, fallback: Long): Long = (remote?.get(key, fallback) as? Number)?.toLong() ?: fallback

    public fun get(key: String, fallback: Double): Double = (remote?.get(key, fallback) as? Number)?.toDouble() ?: fallback

    /** Every parameter that applies to this person, by key. Values are JSON types (`org.json` for arrays and objects). */
    public fun getAll(): Map<String, Any?> = remote?.getAll() ?: emptyMap()

    /** Downloads now, in the background. */
    public fun refresh() {
        remote?.refresh()
    }

    /**
     * Blocks until this launch's download finished or [timeoutMs] passed; true when it finished.
     * Call it from a background thread before the first read to see a config published since the last launch.
     */
    @JvmOverloads
    public fun awaitReady(timeoutMs: Long = Corlex.configTimeoutMs()): Boolean = Corlex.clientOrNull()?.awaitConfigReady(timeoutMs) ?: true

    /** Listens for newer configs; returns the function that stops listening. */
    public fun onUpdate(listener: ConfigUpdateListener): () -> Unit {
        val wrapped = ConfigListener { version, applied -> listener.onUpdate(version, applied) }
        val target = remote
        target?.addListener(wrapped)
        return { target?.removeListener(wrapped) }
    }
}
