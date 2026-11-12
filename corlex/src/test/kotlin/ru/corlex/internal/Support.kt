package ru.corlex.internal

import ru.corlex.CorlexOptions
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.Executor
import java.util.concurrent.ScheduledThreadPoolExecutor

/** A scripted server: answers the queued results in order, then 202. Records every request. */
internal class FakeTransport : Transport {
    val batches = ArrayList<JSONObject>()
    val configRequests = ArrayList<String?>()
    var responses = ArrayDeque<Any>() // HttpResult or IOException
    var configResponse: HttpResult? = null

    @Synchronized
    override fun postBatch(endpoint: String, key: String, body: String): HttpResult {
        batches.add(JSONObject(body).put("_endpoint", endpoint).put("_key", key))
        return when (val next = responses.removeFirstOrNull()) {
            is IOException -> throw next
            is HttpResult -> next
            else -> HttpResult(202, null, ByteArray(0))
        }
    }

    @Synchronized
    override fun getConfig(endpoint: String, key: String, etag: String?): HttpResult {
        configRequests.add(etag)
        return configResponse ?: HttpResult(404, null, ByteArray(0))
    }

    @Synchronized
    fun sentEvents(): List<JSONObject> =
        batches.flatMap { b -> b.getJSONArray("events").let { a -> (0 until a.length()).map { a.getJSONObject(it) } } }

    fun status(code: Int, retryAfter: String? = null) = HttpResult(code, retryAfter, ByteArray(0))
}

internal fun event(id: String, name: String = "e"): JSONObject =
    JSONObject().put("event_id", id).put("name", name).put("properties", JSONObject())

internal fun uuid(n: Int): String = "00000000-0000-4000-8000-%012d".format(n)

internal fun tempDir(): File = Files.createTempDirectory("corlex-test").toFile().also { it.deleteOnExit() }

internal val testDevice = DeviceInfo(appVersion = "1.2.3", build = "42", osVersion = "14", model = "Pixel 8", locale = "en-US")

/** Runs everything on the caller's thread, so tests need no waiting. */
internal val directExecutor = Executor { it.run() }

internal class TestClient(
    options: CorlexOptions = CorlexOptions(flushIntervalMs = 60_000),
    val transport: FakeTransport = FakeTransport(),
    val store: KeyValueStore = MemoryStore(),
    val dir: File = tempDir(),
) {
    val executor = ScheduledThreadPoolExecutor(1)
    val queueFile = File(dir, "queue.jsonl")
    val client = CorlexClient(
        "ck_test_key",
        options,
        ClientDeps(store, queueFile, transport, { testDevice }, executor, directExecutor, { }, { }),
    )

    init {
        client.start()
    }

    /** Waits until everything posted so far ran on the SDK thread. */
    fun settle() {
        executor.submit { }.get()
    }

    fun queued(): List<JSONObject> = if (queueFile.exists()) {
        queueFile.readLines().filter { it.isNotBlank() }.map { JSONObject(it) }
    } else {
        emptyList()
    }

    fun close() = executor.shutdownNow()
}

internal fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
