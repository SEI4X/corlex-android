package ru.corlex

import android.app.Activity
import android.app.Application
import android.content.Context
import android.util.Log
import ru.corlex.internal.AndroidDeps
import ru.corlex.internal.AppLifecycle
import ru.corlex.internal.Delivery
import ru.corlex.internal.isPrimaryProcess
import ru.corlex.internal.isAllowedEndpoint
import ru.corlex.internal.isSdkCredential
import ru.corlex.internal.CorlexClient
import ru.corlex.internal.CrashReporter
import ru.corlex.internal.CrashStore
import ru.corlex.internal.CrashReporterPolicy
import ru.corlex.internal.LifecycleHost
import ru.corlex.internal.ScreenTracker
import java.io.File

/**
 * The Corlex SDK. Call [start] once in `Application.onCreate`; every other call before it does
 * nothing. All calls are safe from any thread and return at once: the work happens on the SDK's
 * own thread.
 *
 * ```
 * class App : Application() {
 *     override fun onCreate() {
 *         super.onCreate()
 *         Corlex.start(this, "ck_live_...")
 *     }
 * }
 * ```
 */
public object Corlex {
    private class Runtime(val client: CorlexClient, val crash: CrashReporter?, val options: CorlexOptions)

    @Volatile private var runtime: Runtime? = null
    private val screens = ScreenTracker { props -> runtime?.client?.track("screen_view", props) }

    /** Remote Config: see [CorlexConfig]. */
    @JvmField
    public val config: CorlexConfig = CorlexConfig()

    /**
     * Starts the SDK: the queue, delivery, `app_open` and `screen_view` tracking, crash reporting and
     * the Remote Config download, as far as [options] and the person's consent allow. Calling it again
     * does nothing.
     */
    @JvmStatic
    @JvmOverloads
    @Synchronized
    public fun start(context: Context, key: String, options: CorlexOptions = CorlexOptions()) {
        if (runtime != null) return
        if (!isPrimaryProcess(context)) {
            Log.w(TAG, "SDK startup is supported only in the application's primary process.")
            return
        }
        if (!isSdkCredential(key)) {
            Log.w(TAG, "invalid_sdk_key")
            return
        }
        if (!isAllowedEndpoint(options.endpoint, options.allowInsecureHttp) ||
            !isAllowedEndpoint(options.configEndpoint, options.allowInsecureHttp)) {
            Log.w(TAG, "start() needs allowed ingest and config endpoints")
            return
        }
        val barrier = Delivery.foregroundBarrier()
        val app = context.applicationContext as Application
        val deps = AndroidDeps.create(app, options.debug, barrier)
        val client = CorlexClient(key, options, deps)
        val crash = if (options.crashReporting) newCrashReporter(app, client, CrashReporterPolicy(deps.device, { client.consent() == Consent.GRANTED }, options.captureErrorMessages)) else null
        runtime = Runtime(client, crash, options)
        client.start()
        crash?.install()
        sendPendingCrashes()
        val host = object : LifecycleHost {
            override fun track(name: String, properties: Map<String, Any?>?) = client.track(name, properties)
            override fun screen(name: String, source: String) = screens.screen(name, source)
            override fun onBackground() = client.onBackground()
        }
        app.registerActivityLifecycleCallbacks(
            AppLifecycle(host, options.trackAppLifecycle, options.trackActivityScreens, context is Activity),
        )
    }

    private fun newCrashReporter(app: Application, client: CorlexClient, policy: CrashReporterPolicy) =
        CrashReporter(
            store = CrashStore(File(app.noBackupFilesDir, "corlex/crashes"), binding = client::crashScope),
            policy = policy,
            killProcess = { android.os.Process.killProcess(android.os.Process.myPid()) },
        )

    private fun sendPendingCrashes() {
        val rt = runtime ?: return
        rt.crash?.let { crash ->
            rt.client.post { if (rt.client.consent() == Consent.GRANTED) crash.sendPending(rt.client) }
        }
    }

    /** Records an event. [name] is 1-128 characters of `[a-z0-9_.:-]`; properties are cut to the protocol's limits. */
    @JvmStatic
    @JvmOverloads
    public fun track(name: String, properties: Map<String, Any?>? = null) {
        runtime?.client?.track(name, properties)
    }

    /** Ties the events of this device to [userId] from now on; [traits] go with the `corlex.identify` event. */
    @JvmStatic
    @JvmOverloads
    public fun identify(userId: String, traits: Map<String, Any?>? = null) {
        runtime?.client?.identify(userId, traits)
    }

    @JvmStatic
    public fun setUserProperties(properties: Map<String, Any?>) {
        runtime?.client?.setUserProperties(properties)
    }

    /**
     * A manual `screen_view`, for navigation the SDK cannot see. The name is a route pattern or a
     * screen name, never what the person opened (ids and query strings are cut out). The same screen
     * twice in a row counts once.
     */
    @JvmStatic
    @JvmOverloads
    public fun screen(name: String, source: String = "manual") {
        screens.screen(name, source)
    }

    /** Shorthand for `config.get(name, default)`. */
    @JvmStatic public fun flag(name: String, default: Boolean): Boolean = config.get(name, default)
    @JvmStatic public fun flag(name: String, default: String): String = config.get(name, default)
    @JvmStatic public fun flag(name: String, default: Int): Int = config.get(name, default)
    @JvmStatic public fun flag(name: String, default: Long): Long = config.get(name, default)
    @JvmStatic public fun flag(name: String, default: Double): Double = config.get(name, default)

    /**
     * The person's variant in a running A/B test, or null when the test is not running, not yet
     * downloaded, or there is no consent (then show the default). Reading it sends one
     * `corlex.exposure` event per person and test per launch.
     */
    @JvmStatic
    public fun experiment(key: String): ExperimentAssignment? =
        runtime?.client?.remote?.experiment(key)?.let { ExperimentAssignment(it.variant, it.value) }

    /** Logout: forget the user id and start a new anonymous person. */
    @JvmStatic
    public fun reset() {
        runtime?.client?.reset()
    }

    /** Sends the queue now, in the background. */
    @JvmStatic
    public fun flush() {
        runtime?.client?.flush()
    }

    /** Where consent stands. [Consent.PENDING]: the SDK holds events in memory and sends nothing. */
    @JvmStatic
    public fun consent(): Consent = runtime?.client?.consent() ?: Consent.PENDING

    /** The person agreed: stores the choice, writes the anonymous id and starts sending what was held. */
    @JvmStatic
    @Synchronized
    public fun optIn() {
        runtime?.client?.optIn()
        sendPendingCrashes()
    }

    /** The person declined: stops collecting and removes everything the SDK stored on the device. */
    @JvmStatic
    @Synchronized
    public fun optOut() {
        val rt = runtime ?: run { Delivery.optOutHeadless(); return }
        rt.client.optOut()
        rt.crash?.let { crash -> rt.client.post { crash.wipe() } }
    }

    internal fun clientOrNull(): CorlexClient? = runtime?.client

    internal fun configTimeoutMs(): Long = runtime?.options?.configTimeoutMs ?: CorlexOptions().configTimeoutMs

    private const val TAG = "Corlex"
}
