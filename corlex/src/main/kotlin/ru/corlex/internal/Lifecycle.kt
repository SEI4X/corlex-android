package ru.corlex.internal

import android.app.Activity
import android.app.Application
import android.os.Bundle

/** What the lifecycle tracker reports to; the client implements it, tests record it. */
internal interface LifecycleHost {
    fun track(name: String, properties: Map<String, Any?>? = null)
    fun screen(name: String, source: String)
    fun onBackground()
}

/**
 * Screens are named by what the developer wrote (Activity class, route pattern), never by what the
 * person opened: ids in a route (`/user/8731`) become `[id]` and query strings are cut off.
 */
internal class ScreenTracker(private val send: (Map<String, Any?>) -> Unit) {
    private var current: String? = null

    /** The same screen reported twice in a row counts once. */
    @Synchronized
    fun screen(name: String, source: String) {
        val safe = safeName(name)
        if (safe.isEmpty() || safe == current) return
        val props = linkedMapOf<String, Any?>("screen" to safe, "source" to source)
        current?.let { props["previous_screen"] = it }
        current = safe
        send(props)
    }

    companion object {
        private const val MAX_SCREEN = 200
        private val UUID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", RegexOption.IGNORE_CASE)
        private val LOOKS_LIKE_ID = Regex("^(\\d{4,}|[0-9a-f]{16,})$", RegexOption.IGNORE_CASE)

        fun safeName(name: String): String {
            val bare = name.split('?', '#').first().trim()
            val cleaned = bare.split('/').joinToString("/") { part ->
                if (UUID.matches(part) || LOOKS_LIKE_ID.matches(part)) "[id]" else part
            }
            return cleaned.take(MAX_SCREEN)
        }
    }
}

/**
 * `app_open` at launch, `app_background` / `app_foreground` from the number of started Activities
 * (a rotation is not a trip to the background), and `screen_view` for each Activity that resumes.
 *
 * A launch started by WorkManager has no Activity, so `app_open` waits for the first one unless
 * the SDK was started from an Activity.
 */
internal class AppLifecycle(
    private val host: LifecycleHost,
    private val trackApp: Boolean,
    private val trackScreens: Boolean,
    startedFromActivity: Boolean,
) : Application.ActivityLifecycleCallbacks {
    private var started = 0
    private var wasBackground = false
    private var openPending = trackApp

    init {
        if (startedFromActivity) sendOpen()
    }

    private fun sendOpen() {
        if (!openPending) return
        openPending = false
        host.track("app_open")
    }

    override fun onActivityStarted(activity: Activity) {
        sendOpen()
        started += 1
        if (started == 1 && wasBackground) {
            wasBackground = false
            if (trackApp) host.track("app_foreground")
        }
    }

    override fun onActivityResumed(activity: Activity) {
        if (trackScreens) host.screen(activity.javaClass.simpleName, "activity")
    }

    override fun onActivityStopped(activity: Activity) {
        started = (started - 1).coerceAtLeast(0)
        if (started == 0 && !activity.isChangingConfigurations) {
            wasBackground = true
            if (trackApp) host.track("app_background")
            host.onBackground()
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
