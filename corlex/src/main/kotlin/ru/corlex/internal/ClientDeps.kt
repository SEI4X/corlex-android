package ru.corlex.internal

import org.json.JSONObject
import java.io.File
import java.util.concurrent.Future
import java.util.concurrent.Executor
import java.util.concurrent.ScheduledExecutorService

/** What an event that happened earlier keeps when it is recorded late (a crash found at the next launch). */
internal class CaptureMeta(
    val timestamp: String? = null,
    val eventId: String? = null,
    val appVersion: String? = null,
    val build: String? = null,
    /** Called on the executor when the event is in the queue, or was dropped for good. */
    val onRecorded: (() -> Unit)? = null,
)

/** Everything the client needs from the outside, so JVM tests can replace the Android parts. */
internal class ClientDeps(
    val store: KeyValueStore,
    val queueFile: File?,
    val transport: Transport,
    val device: () -> DeviceInfo,
    val executor: ScheduledExecutorService,
    val configExecutor: Executor,
    /** Asks WorkManager to deliver the queue when the network is there (survives the process). */
    val scheduleBackground: (delayMs: Long) -> Unit,
    val log: (String) -> Unit,
    val startupBarrier: Future<Unit>? = null,
    val cancelBackground: () -> Unit = {},
    val bindingDisk: BindingDisk? = null,
)

