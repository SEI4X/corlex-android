package ru.corlex.internal

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ru.corlex.Consent
import ru.corlex.CorlexOptions
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 35])
class CrashScopeTest {
    private val ownerA = bindingIdentity("ck_test_" + "a".repeat(43), CorlexOptions())!!
    private val ownerB = bindingIdentity("ck_test_" + "b".repeat(43), CorlexOptions())!!
    private fun report() = CrashReport.build(IllegalStateException("synthetic"), testDevice, "t")

    @Test fun `owner paths isolate crashes while valid sameowner history remains readable`() {
        val root = File(tempDir(), "corlex"); val disk = BindingDisk(root)
        val first = disk.open(ownerA, Consent.GRANTED); var active = first
        val store = CrashStore(File(root, "legacy"), binding = { active })
        val report = report(); store.write(report)
        active = disk.open(ownerB, Consent.GRANTED)
        assertTrue(store.pending().isEmpty())
        active = disk.open(ownerA, Consent.GRANTED)
        assertEquals(report.getString("crash_id"), store.pending().single().getString("crash_id"))
        assertFalse(File(root, "legacy").exists())
    }
    @Test fun `old replay receipts cannot delete a fresh generation with the same crash id`() {
        val root = File(tempDir(), "corlex"); val disk = BindingDisk(root)
        val old = disk.open(ownerA, Consent.GRANTED); var active = old
        val store = CrashStore(File(root, "legacy"), binding = { active })
        val report = report(); store.write(report)
        val receipt = store.pendingRecords().single()
        disk.deny()
        active = disk.open(ownerA, Consent.GRANTED, explicit = true)
        store.write(report)
        receipt.remove()
        assertEquals(1, store.pending().size)
        assertFalse(old.directory!!.exists())
        val stale = CrashStore(File(root, "legacy"), binding = { old })
        stale.write(report())
        assertFalse(old.directory!!.exists())
    }
    @Test fun `memory only crash scopes never persist even if disk becomes writable`() {
        val root = File(tempDir(), "corlex").apply { writeText("blocked") }
        val disk = BindingDisk(root)
        val active = disk.open(ownerA, Consent.GRANTED, explicit = true)
        root.delete(); root.mkdirs()
        val store = CrashStore(File(root, "legacy"), binding = { active })
        store.write(report())
        assertTrue(store.pending().isEmpty())
        assertTrue(root.listFiles().isNullOrEmpty())
    }
    @Test fun `oversized and symlink crash files are rejected without reading or deleting host data`() {
        val root = File(tempDir(), "corlex"); val disk = BindingDisk(root)
        val scope = disk.open(ownerA, Consent.GRANTED)
        val dir = File(scope.directory, "crashes").apply { mkdirs() }
        val report = report(); val name = "crash-${report.getString("crash_id")}.json"
        val original = report.toString()
        val host = File(tempDir(), "host.json").apply { writeText(original) }
        val link = File(dir, name)
        java.nio.file.Files.createSymbolicLink(link.toPath(), host.toPath())
        val store = CrashStore(File(root, "legacy"), binding = { scope })
        assertTrue(store.pending().isEmpty()); assertTrue(host.exists())
        File(dir, name).writeText("x".repeat(CrashStore.MAX_BYTES + 1))
        assertTrue(store.pending().isEmpty()); assertFalse(File(dir, name).exists())
        report.put("crash_id", "../../host")
        store.write(report); store.remove("../../host")
        assertEquals(original, host.readText())
    }
}
