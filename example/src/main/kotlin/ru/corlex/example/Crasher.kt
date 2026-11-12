package ru.corlex.example

import java.io.IOException

/**
 * A deliberate crash with a cause, so the report has a `Caused by` chain. In the minified release
 * build these class and method names are obfuscated; Corlex shows them readable again with the
 * mapping.txt the Gradle plugin uploaded.
 */
object Crasher {
    fun crash() {
        try {
            loadProfile("user-1")
        } catch (e: IOException) {
            throw IllegalStateException("Profile screen failed to load", e)
        }
    }

    private fun loadProfile(userId: String) {
        readFromDisk("profile-$userId.json")
    }

    private fun readFromDisk(name: String) {
        throw IOException("Cannot read $name")
    }
}
