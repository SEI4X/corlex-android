package ru.corlex.compose

import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.composable
import androidx.navigation.createGraph
import androidx.navigation.testing.TestNavHostController
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CorlexNavigationObserverTest {
    @Test fun `each destination is reported by its route pattern, never by its arguments`() {
        val seen = ArrayList<String>()
        val nav = TestNavHostController(ApplicationProvider.getApplicationContext())
        nav.navigatorProvider.addNavigator(ComposeNavigator())
        nav.graph = nav.createGraph(startDestination = "home") {
            composable("home") { }
            composable("profile/{userId}") { }
            composable("search?q={q}") { }
        }
        CorlexNavigationObserver { seen.add(it) }.attachTo(nav)

        nav.navigate("profile/8731")
        nav.navigate("search?q=my%20secret")

        assertEquals(listOf("home", "profile/{userId}", "search?q={q}"), seen)
    }

    @Test fun `a detached observer reports no more`() {
        val seen = ArrayList<String>()
        val nav = TestNavHostController(ApplicationProvider.getApplicationContext())
        nav.navigatorProvider.addNavigator(ComposeNavigator())
        nav.graph = nav.createGraph(startDestination = "home") {
            composable("home") { }
            composable("other") { }
        }
        val observer = CorlexNavigationObserver { seen.add(it) }
        observer.attachTo(nav) // reports the current destination at once
        observer.detachFrom(nav)
        nav.navigate("other")
        assertEquals(listOf("home"), seen)
    }
}
