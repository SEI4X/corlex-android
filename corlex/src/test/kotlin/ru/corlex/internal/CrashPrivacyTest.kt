package ru.corlex.internal

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ru.corlex.CorlexOptions
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CrashPrivacyTest {
    private val time = "2026-11-12T12:00:00.123Z"
    private val sensitive = "synthetic-private@example.test"
    private fun error(): Throwable = IllegalStateException(sensitive, RuntimeException("cause-$sensitive")).also {
        it.stackTrace = arrayOf(StackTraceElement("com.example.Safe", "run", "/Users/synthetic/private/Account.kt?token=example", 42))
    }

    @Test fun `default reports keep static JVM metadata without private messages and source paths`() {
        val report = CrashReport.build(error(), testDevice, time)
        assertFalse(report.toString().contains(sensitive))
        assertFalse(report.toString().contains("/Users/"))
        assertFalse(report.toString().contains("token="))
        assertEquals("java.lang.IllegalStateException", report.getString("name"))
        val frame = report.getJSONArray("frames").getJSONObject(0)
        assertEquals("com.example.Safe.run", frame.getString("fn"))
        assertEquals("Account.kt", frame.getString("file"))
        assertEquals(42, frame.getInt("line"))
    }
    @Test fun `default capture never invokes a dynamic message accessor`() {
        var reads = 0
        val error = object : RuntimeException() { override val message: String get() { reads++; return sensitive } }
        CrashReport.build(error, testDevice, time)
        assertEquals(0, reads)
    }
    @Test fun `replaying previously detailed reports applies the current private default`() {
        val report = CrashReport.build(error(), testDevice, time).put("message", sensitive)
        report.put("frames", JSONArray().put(JSONObject().put("fn", "Caused by: java.lang.RuntimeException: $sensitive")))
        val props = CrashReport.toProperties(report)
        assertFalse(props.toString().contains(sensitive))
        assertTrue((props["frames"] as JSONArray).getJSONObject(0).getString("fn").contains("java.lang.RuntimeException"))
    }
    @Test fun `unrepresentable version and build metadata is unknown rather than a prefix`() {
        for ((version, build) in listOf("v".repeat(129) to "b".repeat(33),
            "a".repeat(125) + "😀" to "b".repeat(29) + "😀", "\uD800" to "\uD800")) {
            val device = DeviceInfo(version, build, "14", "test", "en-US")
            val report = CrashReport.build(error(), device, time)
            assertEquals("", report.getString("app_version"))
            assertEquals("", report.getString("build"))
        }
        val exact = CrashReport.build(error(), DeviceInfo("v".repeat(124) + "😀", "b".repeat(28) + "😀", "14", "test", "en-US"), time)
        assertEquals(128, exact.getString("app_version").toByteArray().size)
        assertEquals(32, exact.getString("build").toByteArray().size)
    }
    @Test fun `message details require explicit opt in and replay can tighten the policy`() {
        assertFalse(CorlexOptions().captureErrorMessages)
        val report = CrashReport.build(error(), testDevice, CrashDetails(time, captureErrorMessages = true))
        assertTrue(report.toString().contains(sensitive))
        assertFalse(report.toString().contains("/Users/"))
        assertTrue(CrashReport.toProperties(report, details = true).toString().contains(sensitive))
        assertFalse(CrashReport.toProperties(report).toString().contains(sensitive))
    }
    @Test fun `long static stacks fit the event budget while retaining top and bottom frames`() {
        val error = IllegalStateException("synthetic")
        error.stackTrace = Array(100) { index ->
            StackTraceElement("com." + "pkg.".repeat(80) + "Frame$index", "run", "SourceFile", index)
        }
        val report = CrashReport.build(error, testDevice, time)
        val frames = report.getJSONArray("frames").objects()
        assertTrue(frames.first().getString("fn").endsWith("Frame0.run"))
        assertTrue(frames.last().getString("fn").endsWith("Frame99.run"))
        val client = TestClient(CorlexOptions(remoteConfig = false, flushIntervalMs = 60_000))
        try {
            client.client.capture(Events.CRASH, CrashReport.toProperties(report), CaptureMeta())
            client.client.flushBlocking()
            assertEquals(1, client.transport.sentEvents().size)
        } finally { client.close() }
    }
    @Test fun `missing historical metadata cannot borrow a new launch version or build`() {
        val report = CrashReport.build(error(), testDevice, time).apply { remove("app_version"); remove("build") }
        val store = CrashStore(File(tempDir(), "crashes")); store.write(report)
        val client = TestClient(CorlexOptions(remoteConfig = false, flushIntervalMs = 60_000))
        try {
            CrashReporter(store, CrashReporterPolicy({ testDevice }, { true }), {}).sendPending(client.client)
            client.client.flushBlocking()
            val event = client.transport.sentEvents().single()
            assertEquals("", event.getJSONObject("context").getString("app_version"))
            assertEquals("", event.getJSONObject("properties").getString("build"))
        } finally { client.close() }
    }
}
