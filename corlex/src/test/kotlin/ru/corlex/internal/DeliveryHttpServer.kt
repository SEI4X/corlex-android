package ru.corlex.internal

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.zip.GZIPInputStream
import kotlin.concurrent.thread

internal class DeliveryHttpServer(
    private val beforeResponse: (Int) -> Unit = {},
    private val status: (Int) -> Int = { 202 },
) : Closeable {
    private val socket = ServerSocket(0, 20, InetAddress.getByName("127.0.0.1"))
    private val connections = CopyOnWriteArrayList<Socket>()
    val requests = CopyOnWriteArrayList<String>()
    val etags = CopyOnWriteArrayList<String?>()
    val firstRequest = CountDownLatch(1)
    val url get() = "http://127.0.0.1:${socket.localPort}"
    private val acceptor = thread(isDaemon = true, name = "delivery-http-fixture") {
        while (!socket.isClosed) {
            val connection = try { socket.accept() } catch (_: IOException) { break }
            connections.add(connection)
            thread(isDaemon = true, name = "delivery-http-request") {
                connection.use { client -> runCatching { respond(client) } }
                connections.remove(connection)
            }
        }
    }

    private fun respond(client: Socket) {
        client.soTimeout = 3000
        val input = client.getInputStream()
        val buffer = ByteArrayOutputStream()
        while (buffer.size() < 16384) {
            val byte = input.read()
            if (byte < 0) break
            buffer.write(byte)
            if (buffer.toString("UTF-8").endsWith("\r\n\r\n")) break
        }
        val headers = buffer.toString("UTF-8")
        etags.add(Regex("(?i)If-None-Match: *([^\r\n]*)").find(headers)?.groupValues?.get(1))
        val length = Regex("(?i)Content-Length: *([0-9]+)").find(headers)?.groupValues?.get(1)?.toInt() ?: 0
        require(length <= 1_048_576)
        val bytes = ByteArray(length)
        var read = 0
        while (read < length) { val count = input.read(bytes, read, length - read); if (count < 0) break; read += count }
        val decoded = if (headers.contains("Content-Encoding: gzip", ignoreCase = true)) GZIPInputStream(bytes.inputStream()).readBytes() else bytes
        requests.add(String(decoded, Charsets.UTF_8))
        val number = requests.size
        firstRequest.countDown()
        beforeResponse(number)
        client.getOutputStream().write("HTTP/1.1 ${status(number)} Result\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
    }

    override fun close() {
        socket.close()
        connections.forEach { runCatching { it.close() } }
        acceptor.join(3000)
    }
}
