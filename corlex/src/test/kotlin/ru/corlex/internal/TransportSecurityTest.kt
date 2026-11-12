package ru.corlex.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class TransportSecurityTest {
    @Test fun `pause cancels an active body and blocks new requests until resumed`() {
        val body = "x".repeat(100)
        SocketHttpServer("HTTP/1.1 200 OK\r\nContent-Length: 100\r\nConnection: close\r\n\r\n$body", 20).use { server ->
            val transport = HttpTransport()
            val result = java.util.concurrent.CompletableFuture<Throwable?>()
            thread(isDaemon = true) {
                result.complete(runCatching { transport.getConfig(server.url, "fake-key", null) }.exceptionOrNull())
            }
            val deadline = System.nanoTime() + 1_000_000_000
            while (server.requests.isEmpty() && System.nanoTime() < deadline) Thread.sleep(5)
            assertEquals(1, server.requests.size)
            transport.pause()
            org.junit.Assert.assertTrue(result.get(1500, TimeUnit.MILLISECONDS) is IOException)
            assertThrows(IOException::class.java) { transport.getConfig(server.url, "fake-key", null) }
            assertEquals(1, server.requests.size)
            transport.resume()
            SocketHttpServer("HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").use { fresh ->
                assertEquals(200, transport.getConfig(fresh.url, "fake-key", null).status)
            }
        }
    }

    @Test fun `response body cannot exceed one MiB`() {
        val body = "x".repeat(1024 * 1024 + 1)
        SocketHttpServer("HTTP/1.1 200 OK\r\nConnection: close\r\n\r\n$body").use { server ->
            assertThrows(IOException::class.java) { HttpTransport().getConfig(server.url, "fake-key", null) }
        }
    }

    @Test fun `total deadline aborts slow bytes without waiting for response completion`() {
        val body = "x".repeat(100)
        SocketHttpServer("HTTP/1.1 200 OK\r\nContent-Length: 100\r\nConnection: close\r\n\r\n$body", 20).use { server ->
            val started = System.nanoTime()
            assertThrows(IOException::class.java) { HttpTransport(totalTimeoutMs = 150).getConfig(server.url, "fake-key", null) }
            assertEquals(1, server.requests.size)
            org.junit.Assert.assertTrue((System.nanoTime() - started) / 1_000_000 < 1500)
        }
    }

    @Test fun `redirects do not forward SDK key or batch payload`() {
        SocketHttpServer("HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").use { sink ->
            val response = "HTTP/1.1 307 Temporary Redirect\r\nLocation: ${sink.url}/leak\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
            SocketHttpServer(response).use { redirect ->
                val transport = HttpTransport()
                assertEquals(307, transport.getConfig(redirect.url, "fake-key", null).status)
                assertEquals(307, transport.postBatch(redirect.url, "fake-key", "{\"events\":[]}").status)
                assertEquals(2, redirect.requests.size)
                assertEquals(0, sink.requests.size)
            }
        }
    }
}

/** Plain loopback HTTP without adding a third-party test transport to the Android SDK. */
private class SocketHttpServer(response: String, delayMs: Long = 0) : Closeable {
    private val socket = ServerSocket(0, 20, InetAddress.getByName("127.0.0.1"))
    val requests = CopyOnWriteArrayList<String>()
    val url get() = "http://127.0.0.1:${socket.localPort}"
    private val worker = thread(isDaemon = true, name = "corlex-http-test") {
        while (!socket.isClosed) {
            val connection = try { socket.accept() } catch (_: IOException) { break }
            connection.use { client ->
                runCatching {
                    client.soTimeout = 3000
                    val input = client.getInputStream()
                    val headers = ByteArrayOutputStream()
                    while (headers.size() < 16384) {
                        val byte = input.read()
                        if (byte < 0) break
                        headers.write(byte)
                        if (headers.toString("UTF-8").endsWith("\r\n\r\n")) break
                    }
                    val request = headers.toString("UTF-8")
                    requests.add(request)
                    val length = Regex("(?i)Content-Length: *([0-9]+)").find(request)?.groupValues?.get(1)?.toInt() ?: 0
                    repeat(length) { input.read() }
                    val output = client.getOutputStream()
                    if (delayMs == 0L) output.write(response.toByteArray(Charsets.UTF_8))
                    else {
                        val split = response.indexOf("\r\n\r\n") + 4
                        output.write(response.substring(0, split).toByteArray(Charsets.UTF_8))
                        for (byte in response.substring(split).toByteArray(Charsets.UTF_8)) {
                            output.write(byte.toInt()); output.flush(); Thread.sleep(delayMs)
                        }
                    }
                }
            }
        }
    }
    override fun close() { socket.close(); worker.join(3000) }
}
