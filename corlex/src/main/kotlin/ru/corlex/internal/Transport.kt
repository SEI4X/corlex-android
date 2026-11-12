package ru.corlex.internal

import ru.corlex.BuildConfig
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPOutputStream
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

internal class HttpResult(val status: Int, val retryAfter: String?, val body: ByteArray, val etag: String? = null)

/** The two requests the SDK makes. Both throw [IOException] when the network fails. */
internal interface Transport {
    /** `POST {endpoint}/v1/batch`. */
    fun postBatch(endpoint: String, key: String, body: String): HttpResult

    /** `GET {endpoint}/v1/config/document` with `If-None-Match` when [etag] is known. */
    fun getConfig(endpoint: String, key: String, etag: String?): HttpResult

    fun pause() { }
    fun resume() { }
}

/**
 * HttpURLConnection instead of OkHttp: the SDK makes two kinds of simple requests, and a bundled
 * HTTP client would add ~1 MB and could clash with the app's own OkHttp version. The key goes in
 * the `X-Corlex-Key` header, never in a URL.
 */
internal class HttpTransport(
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 10_000,
    private val totalTimeoutMs: Long = 10_000,
) : Transport {
    private val lifecycle = Any()
    private val active = HashSet<HttpURLConnection>()
    private var paused = false
    private var epoch = 0L

    override fun pause() {
        val pending = synchronized(lifecycle) {
            paused = true
            epoch++
            active.toList()
        }
        pending.forEach { it.disconnect() }
    }

    override fun resume() { synchronized(lifecycle) { paused = false } }

    override fun postBatch(endpoint: String, key: String, body: String): HttpResult {
        val raw = body.toByteArray(Charsets.UTF_8)
        val gzip = raw.size >= GZIP_THRESHOLD
        return request("$endpoint/v1/batch", "POST", key) { conn ->
            conn.setRequestProperty("Content-Type", "application/json")
            val payload = if (gzip) gzipped(raw).also { conn.setRequestProperty("Content-Encoding", "gzip") } else raw
            conn.doOutput = true
            conn.setFixedLengthStreamingMode(payload.size)
            conn.outputStream.use { it.write(payload) }
        }
    }

    override fun getConfig(endpoint: String, key: String, etag: String?): HttpResult =
        request("$endpoint/v1/config/document", "GET", key) { conn ->
            if (etag != null) conn.setRequestProperty("If-None-Match", etag)
        }

    private fun request(url: String, method: String, key: String, prepare: (HttpURLConnection) -> Unit): HttpResult {
        val conn = URL(url).openConnection() as HttpURLConnection
        val generation = synchronized(lifecycle) {
            if (paused) throw IOException("Corlex transport paused")
            active.add(conn)
            epoch
        }
        fun checkCurrent() = synchronized(lifecycle) {
            if (paused || generation != epoch) throw IOException("Corlex request cancelled")
        }
        val deadline = deadlines.schedule({ conn.disconnect() }, totalTimeoutMs, TimeUnit.MILLISECONDS)
        try {
            checkCurrent()
            conn.requestMethod = method
            conn.connectTimeout = minOf(connectTimeoutMs, totalTimeoutMs.toInt())
            conn.readTimeout = minOf(readTimeoutMs, totalTimeoutMs.toInt())
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("X-Corlex-Key", key)
            conn.setRequestProperty("User-Agent", "corlex-android/${BuildConfig.SDK_VERSION}")
            prepare(conn)
            checkCurrent()
            val status = conn.responseCode
            val stream = if (status >= 400) conn.errorStream else conn.inputStream
            val body = stream?.use { readBounded(it) } ?: ByteArray(0)
            checkCurrent()
            return HttpResult(status, conn.getHeaderField("Retry-After"), body, conn.getHeaderField("ETag"))
        } finally {
            deadline.cancel(false)
            synchronized(lifecycle) { active.remove(conn) }
            conn.disconnect()
        }
    }

    private fun readBounded(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        val started = System.nanoTime()
        while (true) {
            if (System.nanoTime() - started > totalTimeoutMs * 1_000_000) throw IOException("Corlex response timeout")
            val count = input.read(buffer)
            if (count < 0) return output.toByteArray()
            if (count > MAX_RESPONSE_BYTES - output.size()) throw IOException("Corlex response exceeds 1 MiB")
            output.write(buffer, 0, count)
        }
    }

    private fun gzipped(raw: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(raw.size / 4 + 32)
        GZIPOutputStream(out).use { it.write(raw) }
        return out.toByteArray()
    }

    private companion object {
        val deadlines = ScheduledThreadPoolExecutor(1) { runnable ->
            Thread(runnable, "corlex-http-deadline").apply { isDaemon = true }
        }.apply { removeOnCancelPolicy = true }
        const val GZIP_THRESHOLD = 1024
        const val MAX_RESPONSE_BYTES = 1024 * 1024
    }
}
