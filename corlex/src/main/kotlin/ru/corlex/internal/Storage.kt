package ru.corlex.internal

import android.content.Context
import android.content.SharedPreferences

/** Small string settings of the SDK: the consent choice, ids, the saved Remote Config. */
internal interface KeyValueStore {
    fun get(key: String): String?
    fun set(key: String, value: String)
    fun remove(key: String)
}

/** Backed by a private SharedPreferences file. The ids in it are random UUIDs, never device ids. */
internal class PrefsStore(context: Context) : KeyValueStore {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override fun get(key: String): String? = prefs.getString(key, null)
    override fun set(key: String, value: String) = prefs.edit().putString(key, value).apply()
    override fun remove(key: String) = prefs.edit().remove(key).apply()

    companion object {
        const val FILE = "ru.corlex.sdk"
    }
}

internal class MemoryStore : KeyValueStore {
    private val values = HashMap<String, String>()
    @Synchronized override fun get(key: String): String? = values[key]
    @Synchronized override fun set(key: String, value: String) { values[key] = value }
    @Synchronized override fun remove(key: String) { values.remove(key) }
}

/**
 * Who is acting. The anonymous id is a random UUID the SDK makes itself (never the advertising id
 * or ANDROID_ID); `userId` appears after identify(). Until [persist] is called (consent pending)
 * nothing is written.
 */
internal class Identity(private var store: KeyValueStore, private var shouldPersist: Boolean) {
    @Volatile var anonymousId: String = (if (shouldPersist) store.get(ANON_KEY) else null) ?: newAnonymousId()
        private set
    @Volatile var userId: String? = if (shouldPersist) store.get(USER_KEY) else null
        private set

    init {
        if (shouldPersist) persist()
    }

    fun persist() {
        shouldPersist = true
        store.set(ANON_KEY, anonymousId)
        userId?.let { store.set(USER_KEY, it) }
    }

    fun rebind(target: KeyValueStore) { store = target; persist() }

    fun setUser(id: String) {
        userId = truncateBytes(id, Limits.MAX_ID)
        if (shouldPersist) store.set(USER_KEY, userId!!)
    }

    /** Logout: forget the user and start a new anonymous person. */
    fun reset() {
        userId = null
        anonymousId = newAnonymousId()
        store.remove(USER_KEY)
        if (shouldPersist) persist()
    }

    /** Opt-out: remove every trace from storage. */
    fun wipe() {
        store.remove(ANON_KEY)
        store.remove(USER_KEY)
        shouldPersist = false
    }

    private fun newAnonymousId() = "a_${newUuid()}"

    companion object {
        const val ANON_KEY = "corlex_aid"
        const val USER_KEY = "corlex_uid"
    }
}
