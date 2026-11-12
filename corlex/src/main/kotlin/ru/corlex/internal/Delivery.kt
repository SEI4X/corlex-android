package ru.corlex.internal

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import ru.corlex.Corlex
import ru.corlex.CorlexOptions
import java.io.File
import java.util.concurrent.Future
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

private const val TAG = "Corlex"
private const val WORK_NAME = "corlex-delivery"

/**
 * Delivery of the queue while the app is in the background or has no network: a unique one-time
 * work with a `CONNECTED` network constraint. The queue is a file, so it does not matter whether
 * the process lives until the network is back.
 */
internal object DeliveryScheduler {
    fun schedule(context: Context, delayMs: Long) {
        try {
            val request = OneTimeWorkRequestBuilder<DeliveryWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
        } catch (e: IllegalStateException) {
            // WorkManager is not initialized (the app removed its initializer): in-process delivery goes on.
            Log.d(TAG, "WorkManager is not available.")
        }
    }

    fun cancel(context: Context) {
        try { WorkManager.getInstance(context.applicationContext).cancelUniqueWork(WORK_NAME) }
        catch (_: IllegalStateException) { /* The host may deliberately omit WorkManager. */ }
    }
}

/** Runs [Corlex]'s delivery in a worker; started by [DeliveryScheduler]. */
internal class DeliveryWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result = if (Delivery.run(applicationContext, this)) Result.success() else Result.retry()
    override fun onStopped() { Delivery.cancel(this) }
}

internal object Delivery {
    private class Owner(val client: CorlexClient, val worker: Any)
    private var headless: Owner? = null

    /** The existing Corlex monitor only protects ownership, never a network wait. */
    fun run(context: Context, worker: Any = Any()): Boolean {
        if (!isPrimaryProcess(context)) {
            Log.w(TAG, "SDK delivery is supported only in the application's primary process.")
            return true
        }
        val owner = synchronized(Corlex) {
            Corlex.clientOrNull()?.let { Owner(it, worker) } ?: run {
                if (headless != null) return false
                val client = headless(context) ?: return true
                Owner(client, worker).also { headless = it; client.startDeliveryOnly() }
            }
        }
        val temporary = synchronized(Corlex) { headless === owner }
        try {
            val delivered = owner.client.flushBlocking()
            return owner.client.consent() != ru.corlex.Consent.GRANTED || delivered
        } finally {
            if (temporary) {
                release(owner)
            }
        }
    }

    fun foregroundBarrier(): Future<Unit>? = synchronized(Corlex) {
        val previous = headless
        headless = null
        previous?.client?.close()
    }

    fun optOutHeadless() = synchronized(Corlex) {
        headless?.client?.let { it.optOut(); it.close() }
    }

    fun cancel(worker: Any) = synchronized(Corlex) {
        headless?.takeIf { it.worker === worker }?.client?.close()
    }

    private fun release(owner: Owner) {
        while (true) {
            val barrier = owner.client.close()
            val cleanupFailed = runCatching { awaitCleanup(barrier) }.isFailure
            synchronized(Corlex) {
                if (owner.client.close() === barrier) {
                    if (headless === owner) headless = null
                    owner.client.releaseExecutors()
                    if (cleanupFailed) Log.w(TAG, "SDK delivery cleanup failed; the old owner remains stopped.")
                    return
                }
            }
        }
    }

    private fun awaitCleanup(future: Future<Unit>) {
        var interrupted = false
        try {
            while (true) {
                try { future.get(); return }
                catch (_: InterruptedException) { interrupted = true }
            }
        } finally { if (interrupted) Thread.currentThread().interrupt() }
    }

    private fun headless(context: Context): CorlexClient? {
        val disk = BindingDisk(File(context.applicationContext.noBackupFilesDir, "corlex"))
        val auth = disk.workerAuth() ?: return null
        val key = auth.opt("key") as? String ?: return null
        val endpoint = auth.opt("endpoint") as? String ?: return null
        val config = auth.opt("configEndpoint") as? String ?: return null
        val insecure = auth.opt("allowInsecureHttp") as? Boolean ?: return null
        val options = CorlexOptions(
            endpoint = endpoint, configEndpoint = config, allowInsecureHttp = insecure,
            batchSize = auth.optInt("batchSize", 20), remoteConfig = false,
        )
        if (bindingIdentity(key, options) != auth.optString("owner")) return null
        return CorlexClient(key, options, AndroidDeps.create(context, debug = false))
    }
}

internal object AndroidDeps {
    fun create(context: Context, debug: Boolean, startupBarrier: Future<Unit>? = null): ClientDeps {
        val app = context.applicationContext
        return ClientDeps(
            store = MemoryStore(),
            bindingDisk = BindingDisk(File(app.noBackupFilesDir, "corlex")) {
                val legacy = app.getSharedPreferences(PrefsStore.FILE, Context.MODE_PRIVATE)
                val choice = legacy.getString(CorlexClient.CONSENT_KEY, null)
                legacy.edit().clear().commit()
                choice
            },
            queueFile = File(File(app.noBackupFilesDir, "corlex"), "queue.jsonl"),
            transport = HttpTransport(),
            device = DeviceInfo.fromContext(app).let { info -> { info } },
            executor = ScheduledThreadPoolExecutor(1, daemonThreads("corlex")).apply { removeOnCancelPolicy = true },
            configExecutor = Executors.newSingleThreadExecutor(daemonThreads("corlex-config")),
            scheduleBackground = { delayMs -> DeliveryScheduler.schedule(app, delayMs) },
            log = if (debug) { message -> Log.d(TAG, message) } else { _ -> },
            startupBarrier = startupBarrier,
            cancelBackground = { DeliveryScheduler.cancel(app) },
        )
    }

    private fun daemonThreads(name: String) = java.util.concurrent.ThreadFactory { runnable ->
        Thread(runnable, name).apply { isDaemon = true }
    }
}
