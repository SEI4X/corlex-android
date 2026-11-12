package ru.corlex.internal

import org.junit.Assert.*
import org.junit.Test

class EndpointPolicyTest {
    @Test fun `HTTPS and explicit development transports keep their base path`() {
        listOf("https://api.example/sdk", "http://localhost:3102/prefix", "http://127.12.0.2", "http://[::1]", "http://10.0.2.2", "http://10.0.3.2").forEach {
            assertTrue(it, isAllowedEndpoint(it))
        }
        assertFalse(isAllowedEndpoint("http://192.168.1.2"))
        assertTrue(isAllowedEndpoint("http://192.168.1.2/prefix", true))
    }

    @Test fun `credentials and URL metadata never reach transport even with override`() {
        listOf("https://user:secret@example.test", "https://example.test?key=secret", "https://example.test#secret", "https://example.test\n", "file:///tmp/a", "broken", "http://127.evil.test", "http://127.0.0.999").forEach {
            assertFalse(it, isAllowedEndpoint(it))
        }
        listOf("https://user:secret@example.test", "https://example.test?key=secret", "https://example.test#secret").forEach {
            assertFalse(it, isAllowedEndpoint(it, true))
        }
    }
}
