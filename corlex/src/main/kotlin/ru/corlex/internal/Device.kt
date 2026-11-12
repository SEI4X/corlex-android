package ru.corlex.internal

import android.content.Context
import android.os.Build
import ru.corlex.BuildConfig
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * What every event says about the app and the device. `app_version` is `versionName` and goes in
 * `context`; `build` is `versionCode` and travels as an event property (the mobile contract).
 * Nothing here identifies a person: no ANDROID_ID, no advertising id, no IP.
 */
internal class DeviceInfo(
    val appVersion: String?,
    val build: String?,
    val osVersion: String,
    val model: String,
    val locale: String,
) {
    /** The `context` of an event; the protocol keeps only this fixed set of fields. */
    fun toContext(appVersionOverride: String? = null): JSONObject {
        val context = JSONObject().put("platform", "android")
        (appVersionOverride ?: appVersion)?.let { context.put("app_version", truncateBytes(it, Limits.MAX_CONTEXT)) }
        context.put("os", "Android")
        context.put("os_version", truncateBytes(osVersion, Limits.MAX_CONTEXT))
        context.put("device_model", truncateBytes(model, Limits.MAX_CONTEXT))
        context.put("locale", truncateBytes(locale, Limits.MAX_CONTEXT))
        context.put("sdk", JSONObject().put("name", "corlex-android").put("version", BuildConfig.SDK_VERSION))
        return context
    }

    companion object {
        fun fromContext(context: Context): DeviceInfo {
            val app = context.applicationContext
            val info = try {
                app.packageManager.getPackageInfo(app.packageName, 0)
            } catch (_: Exception) {
                null
            }
            @Suppress("DEPRECATION")
            val code = info?.let { if (Build.VERSION.SDK_INT >= 28) it.longVersionCode else it.versionCode.toLong() }
            return DeviceInfo(
                appVersion = info?.versionName,
                build = code?.toString(),
                osVersion = Build.VERSION.RELEASE ?: Build.VERSION.SDK_INT.toString(),
                model = Build.MODEL ?: "unknown",
                locale = Locale.getDefault().toLanguageTag(),
            )
        }
    }
}

internal object Timestamps {
    /** RFC 3339 in UTC with milliseconds, which is what the ingest validator takes. */
    fun format(millis: Long): String {
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        format.timeZone = TimeZone.getTimeZone("UTC")
        return format.format(Date(millis))
    }
}
