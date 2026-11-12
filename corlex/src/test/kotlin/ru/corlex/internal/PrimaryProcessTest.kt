package ru.corlex.internal

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowApplication
import ru.corlex.Corlex
import ru.corlex.CorlexOptions
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 35])
class PrimaryProcessTest {
    @Test fun `matches declared primary process including API 23 fallback`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        app.applicationInfo.processName = "example.primary"
        process(app, "example.primary")
        assertTrue(isPrimaryProcess(app))
        process(app, "example.primary:service")
        assertFalse(isPrimaryProcess(app))
    }

    @Test fun `secondary process start and worker create no identity queue or network`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        app.applicationInfo.processName = "example.primary"
        process(app, "example.primary:service")
        val before = app.noBackupFilesDir.walkTopDown().map { it.relativeTo(app.noBackupFilesDir).path }.toSet()
        DeliveryHttpServer().use { server ->
            Corlex.start(app, "ck_test_" + "x".repeat(43), CorlexOptions(endpoint = server.url, allowInsecureHttp = true))
            assertNull(Corlex.clientOrNull())
            assertTrue(Delivery.run(app))
            assertTrue(server.requests.isEmpty())
        }
        assertTrue(before == app.noBackupFilesDir.walkTopDown().map { it.relativeTo(app.noBackupFilesDir).path }.toSet())
        assertFalse(File(app.filesDir, "corlex").exists())
        assertFalse(File(app.applicationInfo.dataDir, "shared_prefs/${PrefsStore.FILE}.xml").exists())
    }

    private fun process(app: Application, name: String) {
        ShadowApplication.setProcessName(name)
        val manager = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        shadowOf(manager).setProcesses(listOf(ActivityManager.RunningAppProcessInfo(name, Process.myPid(), arrayOf(app.packageName))))
    }
}
