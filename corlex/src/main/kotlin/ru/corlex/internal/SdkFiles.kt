package ru.corlex.internal

import java.io.ByteArrayOutputStream
import java.io.File

internal fun readBoundedSdkFile(source: File, limit: Int): ByteArray? = runCatching {
    if (limit <= 0 || source.length() > limit) return null
    val parent = source.absoluteFile.parentFile ?: return null
    if (source.canonicalFile != File(parent.canonicalFile, source.name)) return null
    source.inputStream().use { input ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (output.size() <= limit) {
            val count = input.read(buffer, 0, minOf(buffer.size, limit + 1 - output.size()))
            if (count < 0) break
            output.write(buffer, 0, count)
        }
        output.toByteArray().takeIf { it.size <= limit }
    }
}.getOrNull()
