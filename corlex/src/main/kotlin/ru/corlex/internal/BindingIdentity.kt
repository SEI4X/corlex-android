package ru.corlex.internal

import org.json.JSONArray
import ru.corlex.CorlexOptions
import java.net.URI
import java.security.MessageDigest

/** Local routing identity only: credentials remain authenticated by the ingest server. */
internal fun bindingIdentity(key: String, options: CorlexOptions): String? {
    if (!isSdkCredential(key)) return null
    if (!isAllowedEndpoint(options.endpoint, options.allowInsecureHttp) ||
        !isAllowedEndpoint(options.configEndpoint, options.allowInsecureHttp)) return null
    return runCatching {
        val tuple = JSONArray().put(key).put(canonicalBase(options.endpoint)).put(canonicalBase(options.configEndpoint))
        MessageDigest.getInstance("SHA-256").digest(tuple.toString().replace("\\/", "/").toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }.getOrNull()
}

private fun canonicalBase(value: String): String {
    val uri = URI(URI(value).toASCIIString())
    val scheme = uri.scheme.lowercase()
    val host = uri.host.lowercase()
    val port = if (uri.port < 0 || (scheme == "https" && uri.port == 443) ||
        (scheme == "http" && uri.port == 80)) "" else ":${uri.port}"
    return "$scheme://$host$port${canonicalPath(uri.rawPath ?: "").trimEnd('/')}"
}

/** URL dot-segment removal preserves empty segments: /a//b and /a/b can route to different cells. */
private fun canonicalPath(raw: String): String {
    val result = ArrayList<String>()
    val segments = raw.split('/')
    for ((index, part) in segments.withIndex()) {
        val dot = part.replace("%2e", ".", ignoreCase = true)
        if (dot == "..") {
            if (result.size > 1) result.removeAt(result.lastIndex)
            if (index == segments.lastIndex) result.add("")
        } else if (dot == ".") {
            if (index == segments.lastIndex) result.add("")
        } else result.add(part)
    }
    return result.joinToString("/")
}

internal fun isSdkCredential(key: String): Boolean = Regex("^ck_(live|test)_[0-9A-Za-z]{43}$").matches(key)
