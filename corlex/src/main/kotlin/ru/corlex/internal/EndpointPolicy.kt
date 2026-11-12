package ru.corlex.internal

import java.net.URI

/** Fail closed without throwing from application startup. Preserve reverse-proxy base paths. */
internal fun isAllowedEndpoint(value: String, allowInsecureHttp: Boolean = false): Boolean {
    if (value.any { it <= ' ' || it == '\u007f' }) return false
    val uri = try { URI(value) } catch (_: Exception) { return false }
    val host = uri.host?.lowercase()?.removePrefix("[")?.removeSuffix("]") ?: return false
    if (uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null || uri.port !in -1..65535) return false
    if (uri.scheme.equals("https", ignoreCase = true)) return true
    if (!uri.scheme.equals("http", ignoreCase = true)) return false
    if (allowInsecureHttp || host in setOf("localhost", "::1", "10.0.2.2", "10.0.3.2")) return true
    val octets = host.split('.')
    return octets.size == 4 && octets.first() == "127" && octets.all { part ->
        part.isNotEmpty() && part.all { it in '0'..'9' } && part.toIntOrNull() in 0..255
    }
}
