package ru.corlex.internal

import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

import ru.corlex.CorlexOptions
import ru.corlex.Consent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** An exception as R8 leaves it: obfuscated class and method names, the source file kept. */
private fun obfuscated(message: String?, cause: Throwable? = null): Throwable {
    val error = IllegalStateException(message, cause)
    error.stackTrace = arrayOf(
        StackTraceElement("a.b.c", "d", "SourceFile", 12),
        StackTraceElement("a.b.e", "f", null, -1),
        StackTraceElement("a.a", "a", "SourceFile", -2),
    )
    return error
}

class CrashReportTest {
    @Test fun `frames are exactly what getStackTrace gave, obfuscated names included`() {
        val frames = CrashReport.frames(obfuscated("boom")).objects()
        assertEquals(3, frames.size)
        assertEquals("a.b.c.d", frames[0].getString("fn"))
        assertEquals("SourceFile", frames[0].getString("file"))
        assertEquals(12, frames[0].getInt("line"))
        assertFalse("no file name, no key", frames[1].has("file"))
        assertFalse("unknown line numbers are left out", frames[1].has("line"))
        assertFalse("native line numbers are left out", frames[2].has("line"))
    }

    @Test fun `the Caused-by chain follows behind a marker frame`() {
        val root = obfuscated("root cause")
        val top = obfuscated("wrapper", root)
        val frames = CrashReport.frames(top, details = true).objects()
        assertEquals(7, frames.size)
        assertEquals("Caused by: java.lang.IllegalStateException: root cause", frames[3].getString("fn"))
        assertFalse(frames[3].has("file"))
        assertEquals("a.b.c.d", frames[4].getString("fn"))
    }

    @Test fun `a cause loop and a long chain end`() {
        val a = IllegalStateException("a").also { it.stackTrace = obfuscated("a").stackTrace }
        val b = obfuscated("b", a)
        a.initCause(b)
        assertEquals(7, CrashReport.frames(b).length())
        var chain: Throwable = obfuscated("0")
        repeat(20) { chain = obfuscated("$it", chain) }
        assertTrue(CrashReport.frames(chain).length() <= 6 * 4 + 5)
    }

    @Test fun `at most 100 frames`() {
        val error = RuntimeException("deep")
        error.stackTrace = Array(400) { StackTraceElement("a.A$it", "m", "SourceFile", it) }
        assertEquals(CrashReport.MAX_FRAMES, CrashReport.frames(error).length())
    }

    @Test fun `a deep top stack does not push the root cause out of the 100 frames`() {
        val root = RuntimeException("root")
        root.stackTrace = Array(30) { StackTraceElement("r.R$it", "m", "SourceFile", it) }
        val top = RuntimeException("top", root)
        top.stackTrace = Array(300) { StackTraceElement("t.T$it", "m", "SourceFile", it) }
        val frames = CrashReport.frames(top, details = true).objects()
        assertEquals(100, frames.size)
        val marker = frames.indexOfFirst { it.getString("fn").startsWith("Caused by:") }
        assertEquals("the top gets the most, the root all its 30", 69, marker)
        assertEquals("r.R29.m", frames.last().getString("fn"))
        assertEquals("t.T0.m", frames.first().getString("fn"))
    }

    @Test fun `name is the class, message is capped at 512 bytes`() {
        val report = CrashReport.build(obfuscated("x".repeat(2000)), testDevice, CrashDetails("2026-10-29T10:00:00.000Z", true))
        assertEquals("java.lang.IllegalStateException", report.getString("name"))
        assertEquals(512, report.getString("message").length)
        assertEquals("java_exception", report.getString("source"))
        assertEquals("1.2.3", report.getString("app_version"))
        assertEquals("42", report.getString("build"))
    }

    @Test fun `a null message is an empty one`() {
        assertEquals("", CrashReport.build(obfuscated(null), testDevice, CrashDetails("t", true)).getString("message"))
    }

    @Test fun `event properties follow the contract`() {
        val report = CrashReport.build(obfuscated("m"), testDevice, "t")
        val props = CrashReport.toProperties(report)
        assertEquals("native", props["kind"])
        assertEquals(true, props["fatal"])
        assertEquals(false, props["symbolicated"])
        assertEquals("java_exception", props["source"])
        assertEquals(report.getString("crash_id"), props["crash_id"])
        assertNotNull(props["frames"])
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CrashStoreAndReporterTest {
    private val clients = ArrayList<TestClient>()
    private var previousDefault: Thread.UncaughtExceptionHandler? = null

    @After fun restore() {
        clients.forEach { it.close() }
        Thread.setDefaultUncaughtExceptionHandler(previousDefault)
    }

    private fun store() = CrashStore(File(tempDir(), "corlex-crashes"))

    @Test fun `a report is written whole and read back, then removed`() {
        val store = store()
        val report = CrashReport.build(obfuscated("m"), testDevice, "2026-10-29T10:00:00.000Z")
        store.write(report)
        val pending = store.pending()
        assertEquals(1, pending.size)
        assertEquals(report.getString("crash_id"), pending[0].getString("crash_id"))
        assertEquals("2026-10-29T10:00:00.000Z", pending[0].getString("timestamp"))
        store.remove(report.getString("crash_id"))
        assertTrue(store.pending().isEmpty())
    }

    @Test fun `a corrupt file is deleted and the others are read`() {
        val dir = File(tempDir(), "corlex-crashes").also { it.mkdirs() }
        File(dir, "crash-bad.json").writeText("{ not json")
        val store = CrashStore(dir)
        store.write(CrashReport.build(obfuscated("ok"), testDevice, "t"))
        assertEquals(1, store.pending().size)
        assertFalse(File(dir, "crash-bad.json").exists())
    }

    @Test fun `only the newest 20 files are sent`() {
        val dir = File(tempDir(), "corlex-crashes")
        val store = CrashStore(dir, maxFiles = 3)
        repeat(5) { store.write(CrashReport.build(obfuscated("$it"), testDevice, "t$it")) }
        assertEquals(3, store.pending().size)
    }

    @Test fun `the handler writes the report and then calls the handler that was there before`() {
        previousDefault = Thread.getDefaultUncaughtExceptionHandler()
        val seen = ArrayList<Throwable>()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> seen.add(e) }
        val before = Thread.getDefaultUncaughtExceptionHandler()
        val store = store()
        val reporter = CrashReporter(store, CrashReporterPolicy({ testDevice }, { true }), { throw AssertionError("must not kill") })
        reporter.install()
        val error = obfuscated("fatal")
        Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(), error)
        assertEquals(listOf<Throwable>(error), seen)
        assertEquals(1, store.pending().size)
        reporter.install() // twice does not chain itself
        reporter.uninstall()
        assertSame("the previous handler is back", before, Thread.getDefaultUncaughtExceptionHandler())
    }

    @Test fun `without a previous handler the process is killed after the report`() {
        previousDefault = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(null)
        var killed = false
        val store = store()
        val reporter = CrashReporter(store, CrashReporterPolicy({ testDevice }, { true }), { killed = true })
        reporter.install()
        Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(), obfuscated("x"))
        assertTrue(killed)
        assertEquals(1, store.pending().size)
    }

    @Test fun `without consent nothing is written, and the app's crash goes on`() {
        previousDefault = Thread.getDefaultUncaughtExceptionHandler()
        var called = false
        Thread.setDefaultUncaughtExceptionHandler { _, _ -> called = true }
        val store = store()
        CrashReporter(store, CrashReporterPolicy({ testDevice }, { false }), { }).also { it.install() }
        Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(), obfuscated("x"))
        assertTrue(called)
        assertTrue(store.pending().isEmpty())
    }

    @Test fun `a crash found at the next launch is sent with its own time, id and app version`() {
        val dir = tempDir()
        val crashStore = CrashStore(File(dir, "corlex-crashes"))
        val oldDevice = DeviceInfo("0.9.0", "7", "13", "Pixel 8", "en-US")
        val report = CrashReport.build(obfuscated("old crash", obfuscated("the cause")), oldDevice, CrashDetails("2026-10-28T08:15:00.123Z", true))
        crashStore.write(report)

        val c = TestClient(CorlexOptions(flushIntervalMs = 60_000), dir = dir).also { clients.add(it) }
        val reporter = CrashReporter(crashStore, CrashReporterPolicy({ testDevice }, { true }, captureErrorMessages = true), { })
        reporter.sendPending(c.client)
        assertTrue(c.client.flushBlocking())

        val event = c.transport.sentEvents().single()
        assertEquals("corlex.crash", event.getString("name"))
        assertEquals(report.getString("crash_id"), event.getString("event_id"))
        assertEquals("2026-10-28T08:15:00.123Z", event.getString("timestamp"))
        assertEquals("0.9.0", event.getJSONObject("context").getString("app_version"))
        val props = event.getJSONObject("properties")
        assertEquals("7", props.getString("build"))
        assertEquals(report.getString("crash_id"), props.getString("crash_id"))
        assertEquals("native", props.getString("kind"))
        assertEquals("java_exception", props.getString("source"))
        assertEquals("java.lang.IllegalStateException", props.getString("name"))
        assertEquals("old crash", props.getString("message"))
        assertFalse(props.getBoolean("symbolicated"))
        val frames = props.getJSONArray("frames").objects()
        assertEquals("a.b.c.d", frames[0].getString("fn"))
        assertTrue(frames.any { it.optString("fn").startsWith("Caused by: java.lang.IllegalStateException: the cause") })
        assertTrue("the file is gone once the event is queued", crashStore.pending().isEmpty())
    }

    @Test fun `a crash is never sent twice`() {
        val dir = tempDir()
        val crashStore = CrashStore(File(dir, "corlex-crashes"))
        crashStore.write(CrashReport.build(obfuscated("once"), testDevice, "2026-10-28T08:15:00.123Z"))
        val c = TestClient(CorlexOptions(flushIntervalMs = 60_000), dir = dir).also { clients.add(it) }
        val reporter = CrashReporter(crashStore, CrashReporterPolicy({ testDevice }, { true }, captureErrorMessages = true), { })
        reporter.sendPending(c.client)
        reporter.sendPending(c.client)
        c.client.flushBlocking()
        assertEquals(1, c.transport.sentEvents().size)
    }

    @Test fun `opt-out wipes unsent crash files`() {
        val crashStore = store()
        crashStore.write(CrashReport.build(obfuscated("x"), testDevice, "t"))
        CrashReporter(crashStore, CrashReporterPolicy({ testDevice }, { false }), { }).wipe()
        assertTrue(crashStore.pending().isEmpty())
    }
}
