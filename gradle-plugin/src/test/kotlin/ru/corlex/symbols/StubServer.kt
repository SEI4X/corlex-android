package ru.corlex.symbols

import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.GZIPInputStream

class RecordedRequest(
    val method: String,
    val path: String,
    val query: Map<String, String>,
    val headers: Map<String, String>,
    val body: String,
)

/** A local HTTP server that records requests and answers with a fixed status. */
class StubServer {
    val requests = CopyOnWriteArrayList<RecordedRequest>()
    private var status = 201
    private var responseBody = "{}"
    private var location: String? = null
    private val server: HttpServer = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)

    val url: String get() = "http://127.0.0.1:${server.address.port}"

    init {
        server.createContext("/") { exchange ->
            val raw = exchange.requestBody.readBytes()
            val gzip = exchange.requestHeaders.getFirst("Content-Encoding") == "gzip"
            val body = (if (gzip) GZIPInputStream(raw.inputStream()).readBytes() else raw).toString(Charsets.UTF_8)
            val query = (exchange.requestURI.rawQuery ?: "").split('&').filter { it.isNotEmpty() }.associate {
                val (key, value) = it.split('=', limit = 2)
                URLDecoder.decode(key, "UTF-8") to URLDecoder.decode(value, "UTF-8")
            }
            val headers = exchange.requestHeaders.entries.associate { it.key.lowercase() to it.value.first() }
            requests.add(RecordedRequest(exchange.requestMethod, exchange.requestURI.path, query, headers, body))
            location?.let { exchange.responseHeaders.add("Location", it) }
            val reply = responseBody.toByteArray()
            exchange.sendResponseHeaders(status, reply.size.toLong())
            exchange.responseBody.use { it.write(reply) }
        }
        server.start()
    }

    fun respond(status: Int, body: String) {
        this.status = status
        responseBody = body
    }

    fun redirectTo(target: String) { status = 307; location = target }

    fun stop() = server.stop(0)
}
