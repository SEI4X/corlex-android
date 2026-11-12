package ru.corlex.internal

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Process

/** One process owns SDK files; Android also calls Application.onCreate in service processes. */
internal fun isPrimaryProcess(context: Context): Boolean {
    val expected = context.applicationInfo.processName ?: context.packageName
    val current = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) Application.getProcessName()
        else (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)
            ?.runningAppProcesses?.firstOrNull { it.pid == Process.myPid() }?.processName
    } catch (_: RuntimeException) { null }
    return current != null && current == expected
}
