package ru.corlex.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.navigation.NavController
import androidx.navigation.NavDestination
import ru.corlex.Corlex

/**
 * Sends a `screen_view` every time Navigation Compose shows a destination. The screen is the route
 * PATTERN (`profile/{userId}`), never the arguments the person navigated with, so ids and search
 * text stay on the device. Attach it with [attachTo] or use [rememberCorlexScreens].
 */
public class CorlexNavigationObserver(
    private val report: (String) -> Unit = { Corlex.screen(it, SOURCE) },
) : NavController.OnDestinationChangedListener {

    override fun onDestinationChanged(
        controller: NavController,
        destination: NavDestination,
        arguments: android.os.Bundle?,
    ) {
        screenName(destination)?.let(report)
    }

    public fun attachTo(navController: NavController) {
        navController.addOnDestinationChangedListener(this)
    }

    public fun detachFrom(navController: NavController) {
        navController.removeOnDestinationChangedListener(this)
    }

    internal companion object {
        const val SOURCE = "compose"

        /** The route pattern, or the destination's label when it has no route; nested graphs are not screens. */
        fun screenName(destination: NavDestination): String? {
            if (destination is androidx.navigation.NavGraph) return null
            return destination.route ?: destination.label?.toString()
        }
    }
}

/**
 * Reports the screens of [navController] for as long as the caller is in the composition:
 *
 * ```
 * val navController = rememberNavController()
 * rememberCorlexScreens(navController)
 * NavHost(navController, startDestination = "home") { ... }
 * ```
 *
 * The SDK's own Activity tracking is redundant in such an app; start it with
 * `CorlexOptions(trackActivityScreens = false)`.
 */
@Composable
public fun rememberCorlexScreens(navController: NavController): CorlexNavigationObserver {
    val observer = remember(navController) { CorlexNavigationObserver() }
    DisposableEffect(navController, observer) {
        observer.attachTo(navController)
        onDispose { observer.detachFrom(navController) }
    }
    return observer
}

/** A `screen_view` for a screen that is not a navigation destination (a pager page, a tab, a dialog). */
@Composable
public fun CorlexScreen(name: String) {
    LaunchedEffect(name) { Corlex.screen(name, CorlexNavigationObserver.SOURCE) }
}
