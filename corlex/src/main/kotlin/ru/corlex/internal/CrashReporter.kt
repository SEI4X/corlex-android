package ru.corlex.internal

import java.io.IOException

/**
 * Uncaught-exception capture: a default handler that writes the report to a file, then calls the
 * handler that was installed before (Crashlytics, Sentry, the Android runtime's own), so installing
 * Corlex next to another reporter changes nothing for that one. Nothing is written without consent.
 */
internal class CrashReporter(
    private val store: CrashStore,
    private val policy: CrashReporterPolicy,
    private val killProcess: () -> Unit,
) {
    private class Handler(
        val previous: Thread.UncaughtExceptionHandler?,
        val reporter: CrashReporter,
    ) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(thread: Thread, error: Throwable) {
            reporter.record(error)
            if (previous != null) previous.uncaughtException(thread, error) else reporter.killProcess()
        }
    }

    @Synchronized
    fun install() {
        val current = Thread.getDefaultUncaughtExceptionHandler()
        if (current is Handler && current.reporter === this) return
        Thread.setDefaultUncaughtExceptionHandler(Handler(current, this))
    }

    /** Puts the previous handler back, if ours is still the default one. */
    @Synchronized
    fun uninstall() {
        val current = Thread.getDefaultUncaughtExceptionHandler()
        if (current is Handler && current.reporter === this) Thread.setDefaultUncaughtExceptionHandler(current.previous)
    }

    /** Writes the report. A failing crash reporter must never replace the crash it was reporting. */
    fun record(error: Throwable) {
        try {
            if (!policy.enabled()) return
            store.write(CrashReport.build(error, policy.device(), CrashDetails(Timestamps.format(System.currentTimeMillis()), policy.captureErrorMessages)))
        } catch (_: IOException) {
            // Disk full or gone.
        } catch (_: RuntimeException) {
            // Anything else: the app's crash goes on regardless.
        } catch (_: StackOverflowError) {
            // A StackOverflowError that reaches the handler may overflow again here.
        } catch (_: OutOfMemoryError) {
            // Same.
        }
    }

    /** Sends the crashes of earlier launches as `corlex.crash`, once each; the file goes after it is queued. */
    fun sendPending(client: CorlexClient) {
        for (pending in store.pendingRecords()) {
            val report = pending.report
            val id = report.optString("crash_id")
            if (id.isEmpty()) continue
            client.capture(
                Events.CRASH,
                CrashReport.toProperties(report, policy.captureErrorMessages),
                CaptureMeta(
                    timestamp = report.optString("timestamp").ifEmpty { null },
                    eventId = id,
                    appVersion = CrashPrivacy.metadata(report.opt("app_version"), 128),
                    build = CrashPrivacy.metadata(report.opt("build"), 32),
                    onRecorded = pending.remove,
                ),
            )
        }
    }

    fun wipe() = store.wipe()
}
