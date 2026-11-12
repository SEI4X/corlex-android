package ru.corlex.internal

import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.FutureTask
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Serial cleanup barriers keep an old queue owner from writing after a takeover. */
internal class ClientLifetime(private val deps: ClientDeps) {
    @Volatile var closed = false
        private set
    val epoch = AtomicLong()
    val initializedReady = CountDownLatch(1)
    @Volatile var initialized = false
    @Volatile var startupFailed = false
    @Volatile var desiredChoice: ru.corlex.Consent? = null
    private var closing: Future<Unit>? = null

    fun failStartup() { startupFailed = true; initializedReady.countDown(); deps.transport.pause() }

    fun awaitConfig(timeoutMs: Long, remote: RemoteConfig?): Boolean {
        val start = System.nanoTime()
        val limit = timeoutMs.coerceAtLeast(0)
        if (!initializedReady.await(limit, TimeUnit.MILLISECONDS)) return false
        val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
        return remote?.awaitReady((limit - elapsed).coerceAtLeast(0)) ?: true
    }

    @Synchronized fun deny(cleanup: () -> Unit) {
        epoch.incrementAndGet()
        deps.transport.pause()
        deps.cancelBackground()
        val task = FutureTask<Unit> { cleanup() }
        if (closed) closing = task
        deps.executor.execute(task)
    }

    @Synchronized fun close(cleanup: () -> Unit): Future<Unit> {
        closing?.let { return it }
        closed = true
        initializedReady.countDown()
        epoch.incrementAndGet()
        deps.transport.pause()
        val task = FutureTask<Unit> { cleanup() }
        closing = task
        deps.executor.execute(task)
        return task
    }

    /** Only after the owner is detached and its latest cleanup barrier completed. */
    fun release() {
        (deps.configExecutor as? ExecutorService)?.shutdownNow()
        deps.executor.shutdown()
    }
}
