package ru.corlex.symbols

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.util.zip.GZIPOutputStream

/** What the upload needs. [token] is a project upload token (`corlex_up_...`); it is never logged. */
public class MappingUpload(
    public val apiUrl: String,
    public val projectId: String,
    public val token: String,
    public val version: String,
    public val build: String,
    public val file: File,
)

/** How an upload ended. None of these fails the build. */
public sealed interface UploadResult {
    public data class Uploaded(val key: String?) : UploadResult
    public data class Rejected(val status: Int) : UploadResult
    public data class Failed(val reason: String) : UploadResult
}

/**
 * `POST /api/projects/:projectId/symbols?platform=android&kind=r8_mapping&version=...&build=...`
 * with the raw mapping.txt gzipped as the body and the upload token as a Bearer token (the mobile
 * contract in services/ingest/docs/protocol.md). A 5xx or a network error is tried once more.
 */
public object MappingUploader {
    private const val ATTEMPTS = 2
    private const val RETRY_PAUSE_MS = 1500L

    public fun upload(request: MappingUpload, pause: (Long) -> Unit = Thread::sleep): UploadResult {
        val body = gzip(request.file.readBytes())
        var last: UploadResult = UploadResult.Failed("no attempt was made")
        repeat(ATTEMPTS) { attempt ->
            if (attempt > 0) pause(RETRY_PAUSE_MS)
            last = send(request, body)
            if (!shouldRetry(last)) return last
        }
        return last
    }

    private fun shouldRetry(result: UploadResult): Boolean =
        result is UploadResult.Failed || (result is UploadResult.Rejected && result.status >= 500)

    internal fun endpoint(request: MappingUpload): String {
        fun enc(value: String) = URLEncoder.encode(value, Charsets.UTF_8.name())
        val base = request.apiUrl.trimEnd('/')
        return "$base/api/projects/${enc(request.projectId)}/symbols" +
            "?platform=android&kind=r8_mapping&version=${enc(request.version)}&build=${enc(request.build)}"
    }

    private fun send(request: MappingUpload, body: ByteArray): UploadResult {
        val connection = try {
            URI(endpoint(request)).toURL().openConnection() as HttpURLConnection
        } catch (e: java.net.MalformedURLException) {
            return UploadResult.Failed("the API URL is not valid")
        } catch (e: IllegalArgumentException) {
            return UploadResult.Failed("the API URL is not valid")
        }
        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 15_000
            connection.readTimeout = 120_000
            connection.instanceFollowRedirects = false
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(body.size)
            connection.setRequestProperty("Authorization", "Bearer ${request.token}")
            connection.setRequestProperty("Content-Type", "text/plain")
            connection.setRequestProperty("Content-Encoding", "gzip")
            connection.outputStream.use { it.write(body) }
            result(connection)
        } catch (e: IOException) {
            // The exception text can hold the URL; the URL holds no secret, but keep the message short.
            UploadResult.Failed(e.javaClass.simpleName)
        } finally {
            connection.disconnect()
        }
    }

    private fun result(connection: HttpURLConnection): UploadResult {
        val status = connection.responseCode
        if (status != 201 && status != 200) return UploadResult.Rejected(status)
        val text = connection.inputStream.use { readBounded(it) }.toString(Charsets.UTF_8)
        return UploadResult.Uploaded(Regex("\"key\"\\s*:\\s*\"([^\"]*)\"").find(text)?.groupValues?.get(1))
    }

    private fun readBounded(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        val started = System.nanoTime()
        while (true) {
            if (System.nanoTime() - started > 120_000_000_000L) throw IOException("Corlex upload response timeout")
            val count = input.read(buffer)
            if (count < 0) return output.toByteArray()
            if (count > 1024 * 1024 - output.size()) throw IOException("Corlex upload response exceeds 1 MiB")
            output.write(buffer, 0, count)
        }
    }

    private fun gzip(raw: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(raw.size / 4 + 64)
        GZIPOutputStream(out).use { it.write(raw) }
        return out.toByteArray()
    }
}
