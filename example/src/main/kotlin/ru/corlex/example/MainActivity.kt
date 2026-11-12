package ru.corlex.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import ru.corlex.Corlex
import ru.corlex.compose.rememberCorlexScreens

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) { ExampleNav() } } }
    }
}

@Composable
private fun ExampleNav() {
    val nav = rememberNavController()
    // One line: every destination the NavHost shows becomes a screen_view (route pattern only).
    rememberCorlexScreens(nav)
    NavHost(nav, startDestination = "home") {
        composable("home") { HomeScreen(onOpen = { id -> nav.navigate("item/$id") }, onSettings = { nav.navigate("settings") }) }
        composable("item/{id}") { ItemScreen(onBack = { nav.popBackStack() }) }
        composable("settings") { SettingsScreen(onBack = { nav.popBackStack() }) }
    }
}

@Composable
private fun HomeScreen(onOpen: (Int) -> Unit, onSettings: () -> Unit) {
    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Corlex example", style = MaterialTheme.typography.headlineMedium)
        Text("paywall flag: ${Corlex.flag("paywall", false)}")
        Button(onClick = { Corlex.track("button_tap", mapOf("button" to "track", "n" to 1)) }) { Text("Track event") }
        Button(onClick = { Corlex.identify("user_42", mapOf("plan" to "pro")) }) { Text("Identify user") }
        Button(onClick = { onOpen(8731) }) { Text("Open item 8731") }
        Button(onClick = onSettings) { Text("Settings") }
        Button(onClick = { Crasher.crash() }) { Text("Crash") }
    }
}

@Composable
private fun ItemScreen(onBack: () -> Unit) {
    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Item", style = MaterialTheme.typography.headlineMedium)
        Button(onClick = { Corlex.track("item_like") }) { Text("Like") }
        Button(onClick = onBack) { Text("Back") }
    }
}

@Composable
private fun SettingsScreen(onBack: () -> Unit) {
    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Privacy", style = MaterialTheme.typography.headlineMedium)
        Button(onClick = { Corlex.optIn() }) { Text("Allow analytics") }
        Button(onClick = { Corlex.optOut() }) { Text("Stop analytics") }
        Button(onClick = { Corlex.reset() }) { Text("Log out") }
        Button(onClick = onBack) { Text("Back") }
    }
}
