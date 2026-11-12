package ru.corlex.internal

import android.app.Activity
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

class ScreenTrackerTest {
    @Test fun `ids and query strings never leave the device`() {
        assertEquals("/user/[id]", ScreenTracker.safeName("/user/8731"))
        assertEquals("/item/[id]/edit", ScreenTracker.safeName("/item/0192f3a0-0000-7000-8000-000000000001/edit"))
        assertEquals("/search", ScreenTracker.safeName("/search?q=secret#top"))
        assertEquals("Settings/Profile", ScreenTracker.safeName("Settings/Profile"))
        assertEquals("/page/2", ScreenTracker.safeName("/page/2"))
    }

    @Test fun `the same screen twice in a row counts once and the next carries previous_screen`() {
        val sent = ArrayList<Map<String, Any?>>()
        val tracker = ScreenTracker { sent.add(it) }
        tracker.screen("Home", "activity")
        tracker.screen("Home", "activity")
        tracker.screen("Detail", "compose")
        assertEquals(2, sent.size)
        assertEquals(mapOf("screen" to "Home", "source" to "activity"), sent[0])
        assertEquals(mapOf("screen" to "Detail", "source" to "compose", "previous_screen" to "Home"), sent[1])
    }
}

class MainActivity : Activity()
class SecondActivity : Activity()

/** The Activity callbacks on a real (Robolectric) Application: what a device would deliver. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppLifecycleTest {
    private class RecordingHost : LifecycleHost {
        val log = ArrayList<String>()
        override fun track(name: String, properties: Map<String, Any?>?) { log.add(name) }
        override fun screen(name: String, source: String) { log.add("screen:$name:$source") }
        override fun onBackground() { log.add("flush") }
    }

    private val host = RecordingHost()
    private lateinit var app: Application

    @Before fun setUp() {
        app = ApplicationProvider.getApplicationContext()
    }

    private fun register(trackApp: Boolean = true, trackScreens: Boolean = true, fromActivity: Boolean = false) =
        app.registerActivityLifecycleCallbacks(AppLifecycle(host, trackApp, trackScreens, fromActivity))

    @Test fun `app_open waits for the first Activity, screen_view follows, background and foreground are paired`() {
        register()
        assertEquals("no Activity yet (a WorkManager launch), so no app_open", emptyList<String>(), host.log)

        val main = Robolectric.buildActivity(MainActivity::class.java).create().start().resume()
        assertEquals(listOf("app_open", "screen:MainActivity:activity"), host.log)

        main.pause().stop()
        assertEquals(listOf("app_open", "screen:MainActivity:activity", "app_background", "flush"), host.log)

        host.log.clear()
        main.restart().start().resume()
        assertEquals(listOf("app_foreground", "screen:MainActivity:activity"), host.log)
    }

    @Test fun `app_open is sent once per launch, not per Activity`() {
        register()
        val main = Robolectric.buildActivity(MainActivity::class.java).create().start().resume()
        val second = Robolectric.buildActivity(SecondActivity::class.java).create().start().resume()
        main.pause().stop()
        assertEquals(1, host.log.count { it == "app_open" })
        assertEquals(listOf("screen:MainActivity:activity", "screen:SecondActivity:activity"), host.log.filter { it.startsWith("screen") })
        assertEquals("another Activity is still started, so the app is not in the background", 0, host.log.count { it == "app_background" })
        second.pause().stop()
        assertEquals(1, host.log.count { it == "app_background" })
    }

    @Test fun `a rotation is not a trip to the background`() {
        register()
        val main = Robolectric.buildActivity(MainActivity::class.java).create().start().resume()
        host.log.clear()
        main.configurationChange()
        assertEquals(0, host.log.count { it == "app_background" || it == "app_foreground" })
    }

    @Test fun `started from an Activity, app_open is sent at once`() {
        register(fromActivity = true)
        assertEquals(listOf("app_open"), host.log)
    }

    @Test fun `switches turn parts off`() {
        register(trackApp = false, trackScreens = false)
        val main = Robolectric.buildActivity(MainActivity::class.java).create().start().resume()
        main.pause().stop()
        assertEquals(listOf("flush"), host.log)
    }
}
