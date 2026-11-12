package ru.corlex.symbols

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MappingSecurityTest {
    private fun request(server: StubServer, file: File) = MappingUpload(server.url, "project", "fake-upload-token", "1.0", "1", file)

    @Test fun `upload reply is bounded without failing the application build`() {
        val file = File.createTempFile("corlex-mapping", ".txt").apply { writeText("sample -> a:\n") }
        val server = StubServer()
        server.respond(201, "x".repeat(1024 * 1024 + 1))
        try {
            val result = MappingUploader.upload(request(server, file), pause = {})
            assertTrue("oversized response must not count as uploaded", result is UploadResult.Failed)
        } finally { server.stop(); file.delete() }
    }

    @Test fun `redirects never forward bearer token and mapping contents`() {
        val file = File.createTempFile("corlex-mapping", ".txt").apply { writeText("sample -> a:\n") }
        val server = StubServer()
        val sink = StubServer()
        server.redirectTo(sink.url)
        try {
            val result = MappingUploader.upload(request(server, file), pause = {})
            assertEquals(UploadResult.Rejected(307), result)
            assertEquals(1, server.requests.size)
            assertTrue(sink.requests.isEmpty())
        } finally { server.stop(); sink.stop(); file.delete() }
    }
}
