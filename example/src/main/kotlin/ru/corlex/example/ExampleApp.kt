package ru.corlex.example

import android.app.Application
import ru.corlex.Corlex
import ru.corlex.CorlexOptions

class ExampleApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Corlex.start(
            this,
            BuildConfig.CORLEX_KEY,
            CorlexOptions(
                endpoint = BuildConfig.CORLEX_ENDPOINT,
                configEndpoint = BuildConfig.CORLEX_ENDPOINT,
                batchSize = 5,
                flushIntervalMs = 2000,
                // Screens come from Navigation Compose (rememberCorlexScreens), not from the Activity.
                trackActivityScreens = false,
                debug = true,
            ),
        )
    }
}
