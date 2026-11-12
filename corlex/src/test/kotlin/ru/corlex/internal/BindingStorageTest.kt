package ru.corlex.internal

import androidx.test.core.app.ApplicationProvider
import android.app.Application
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ru.corlex.Consent
import ru.corlex.CorlexOptions
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 35])
class BindingStorageTest {
    private val key = "ck_test_" + "x".repeat(43)
    private val options = CorlexOptions(endpoint = "https://ingest.example.test", configEndpoint = "https://config.example.test")
    private val a get() = bindingIdentity(key, options)!!
    private val b get() = bindingIdentity(key.replace("test", "live"), options)!!
    private fun root() = Files.createTempDirectory("corlex-binding-test").toFile()

    @Test fun `digest matches JSON SHA vector and normalized full endpoint boundaries`() {
        assertEquals("2e940110f634ae30dc358a01db9879196e84898b4f83a53e6e6e1b550642d8d0", a)
        assertEquals(a, bindingIdentity(key, CorlexOptions(endpoint = "https://INGEST.example.test:443/", configEndpoint = "https://CONFIG.example.test/")))
        assertNotEquals(a, bindingIdentity(key, CorlexOptions(endpoint = "https://ingest.example.test/cell-a", configEndpoint = options.configEndpoint)))
        assertNotEquals(a, b)
        assertNotEquals(bindingIdentity(key, CorlexOptions(endpoint = "https://ingest.example.test/a//b")),
            bindingIdentity(key, CorlexOptions(endpoint = "https://ingest.example.test/a/b")))
        assertEquals(bindingIdentity(key, CorlexOptions(endpoint = "https://ingest.example.test/a/%2e%2e/b")),
            bindingIdentity(key, CorlexOptions(endpoint = "https://ingest.example.test/b")))
        for (bad in listOf("cm_example", "corlex_up_example", "sk_example", key + "x")) assertNull(bindingIdentity(bad, options))
    }
    @Test fun `independent owner stores and valid same owner restarts preserve history`() {
        val root = root(); val disk = BindingDisk(root)
        val first = disk.open(a, Consent.GRANTED); first.set("queue", "a-only")
        val other = disk.open(b, Consent.GRANTED); other.set("queue", "b-only")
        assertEquals("a-only", BindingDisk(root).open(a, Consent.GRANTED).get("queue"))
        assertEquals("b-only", other.get("queue"))
        assertEquals("a-only", first.get("queue"))
        assertFalse(File(first.directory, "state.json").readText().contains(key))
    }
    @Test fun `denial fences all owners before delayed wipe and regrant never revives old state`() {
        val root = root(); val disk = BindingDisk(root)
        val first = disk.open(a, Consent.GRANTED); first.set("id", "old-a")
        val other = disk.open(b, Consent.GRANTED); other.set("id", "old-b")
        val denied = disk.fenceDenied()
        assertNull(first.get("id")); first.set("id", "late-a")
        assertEquals(Consent.DENIED, other.consent())
        val grant = disk.open(b, Consent.GRANTED, explicit = true)
        grant.set("id", "fresh-b")
        disk.deny(denied) // Old cleanup must never remove the new generation's data.
        assertEquals("fresh-b", grant.get("id"))
        assertFalse(first.directory!!.exists())
        assertNull(disk.open(a, Consent.GRANTED).get("id"))
        assertNull(first.get("id"))
    }
    @Test fun `pending and legacy denial persist but unbound identities never migrate`() {
        val root = root(); val pending = BindingDisk(root).open(a, Consent.PENDING)
        pending.set("id", "must-not-store")
        assertNull(pending.get("id"))
        assertFalse(File(pending.directory, "state.json").exists())
        assertEquals(Consent.PENDING, BindingDisk(root).open(b, Consent.GRANTED).consent())
        val legacy = BindingDisk(root(), { "denied" }).open(a, Consent.GRANTED)
        assertEquals(Consent.DENIED, legacy.consent())
    }
    @Test fun `unreadable privacy stays pending and explicit opt in uses new memory only`() {
        val root = root(); root.delete(); root.writeText("unwritable-directory")
        val disk = BindingDisk(root)
        val pending = disk.open(a, Consent.GRANTED)
        assertEquals(Consent.PENDING, pending.consent())
        val granted = disk.open(a, Consent.GRANTED, explicit = true)
        assertFalse(granted.persistent)
        assertEquals(Consent.GRANTED, granted.consent())
        granted.set("id", "fresh-memory")
        assertEquals("fresh-memory", granted.get("id"))
    }
    @Test fun `fresh pending owner fences earlier grants and stays pending across another credential`() {
        val disk = BindingDisk(root())
        val first = disk.open(a, Consent.GRANTED)
        assertEquals(Consent.GRANTED, disk.open(a, Consent.PENDING).consent())
        val pending = disk.open(b, Consent.PENDING)
        assertEquals(Consent.PENDING, disk.open(a, Consent.GRANTED).consent())
        assertEquals(Consent.PENDING, first.consent())
        assertEquals(Consent.GRANTED, disk.open(b, Consent.GRANTED, explicit = true).consent())
        assertEquals(Consent.PENDING, first.consent())
        assertEquals(Consent.PENDING, pending.consent())
    }
    @Test fun `guarded operations can reenter the same SDK file lock without losing state`() {
        val disk = BindingDisk(root()); val scope = disk.open(a, Consent.GRANTED)
        val actual = scope.guard { scope.set("id", "nested"); scope.get("id") }
        assertEquals("nested", actual)
    }
    @Test fun `opt out fence never waits for a blocked disk operation and rejects its old result`() {
        val disk = BindingDisk(root()); val scope = disk.open(a, Consent.GRANTED)
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val threads = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val pending = threads.submit<String?> { scope.guard { entered.countDown(); release.await(); "old-result" } }
            assertTrue(entered.await(3, java.util.concurrent.TimeUnit.SECONDS))
            val denied = threads.submit<PrivacyFence> { disk.fenceDenied() }.get(1, java.util.concurrent.TimeUnit.SECONDS)
            release.countDown()
            assertNull(pending.get(3, java.util.concurrent.TimeUnit.SECONDS))
            disk.deny(denied)
        } finally { release.countDown(); threads.shutdownNow() }
    }
    @Test fun `a concurrent denial cannot be overwritten by an older initialization`() {
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val root = root()
        val disk = BindingDisk(root) { entered.countDown(); release.await(); null }
        val threads = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val opening = threads.submit<BoundStorage> { disk.open(a, Consent.GRANTED) }
            assertTrue(entered.await(3, java.util.concurrent.TimeUnit.SECONDS))
            val denied = threads.submit<PrivacyFence> { disk.fenceDenied() }.get(1, java.util.concurrent.TimeUnit.SECONDS)
            release.countDown()
            assertEquals(Consent.DENIED, opening.get(3, java.util.concurrent.TimeUnit.SECONDS).consent())
            disk.deny(denied)
            assertEquals("DENIED", org.json.JSONObject(File(root, "privacy.json").readText()).getString("state"))
        } finally { release.countDown(); threads.shutdownNow() }
    }
    @Test fun `global denial cancels all live scopes immediately before durable cleanup`() {
        val disk = BindingDisk(root()); val first = disk.open(a, Consent.GRANTED)
        val other = disk.open(b, Consent.GRANTED)
        var cancelled = 0
        val watchA = disk.watch(first) { cancelled++ }
        val watchB = disk.watch(other) { cancelled++ }
        val fence = disk.fenceDenied()
        assertEquals(2, cancelled)
        watchA.close(); watchB.close()
        disk.deny(fence)
        assertEquals(2, cancelled)
    }
    @Test fun `malformed global privacy cannot be treated as missing fresh consent`() {
        for (text in listOf("not-json", "{\"generation\":\"${java.util.UUID.randomUUID()}\",\"state\":\"DENIED\",\"state\":\"GRANTED\"}")) {
            val root = root(); File(root, "privacy.json").writeText(text)
            val disk = BindingDisk(root)
            assertEquals(Consent.PENDING, disk.open(a, Consent.GRANTED).consent())
            val grant = disk.open(a, Consent.GRANTED, explicit = true)
            assertEquals(Consent.GRANTED, grant.consent())
            assertNull(grant.get("id"))
        }
    }
    @Test fun `failed disk writes still fence existing memory only sessions on opt out`() {
        val root = root(); root.delete(); root.writeText("unwritable-directory")
        val disk = BindingDisk(root)
        val first = disk.open(a, Consent.GRANTED, explicit = true)
        first.set("id", "memory")
        disk.deny()
        assertEquals(Consent.DENIED, first.consent())
        assertNull(first.get("id"))
    }
    @Test fun `protected storage is SDK only and opt out never removes host files`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val root = File(app.noBackupFilesDir, "binding-test-${java.util.UUID.randomUUID()}")
        val host = File(app.filesDir, "host-test-${java.util.UUID.randomUUID()}").apply { writeText("keep") }
        val disk = BindingDisk(root); val scope = disk.open(a, Consent.GRANTED)
        scope.set("config", "old")
        disk.deny()
        assertTrue(host.exists())
        assertFalse(scope.directory!!.exists())
        assertEquals(Consent.DENIED, BindingDisk(root).open(b, Consent.GRANTED).consent())
        host.delete()
    }
}
