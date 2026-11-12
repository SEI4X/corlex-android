package ru.corlex.internal

/** Mirrors services/ingest/internal/event/validate.go and packages/sdk-core/src/limits.ts. */
internal object Limits {
    const val MAX_ID = 256
    const val MAX_CONTEXT = 128
    const val MAX_PROPERTIES = 100
    const val MAX_PROPERTY_KEY = 128
    const val MAX_PROPERTY_STRING = 1024
    const val MAX_ARRAY = 100
    const val MAX_DEPTH = 4
    const val MAX_BATCH_EVENTS = 500
    const val MAX_EVENT_BYTES = 32_768

    /** Oldest events go first when a person stays offline for long. */
    const val MAX_QUEUE = 1000

    /** The queue file never grows beyond this; the oldest events are dropped first. */
    const val MAX_QUEUE_BYTES = 4 * 1024 * 1024

    val NAME_PATTERN = Regex("^[a-z0-9_.:-]{1,128}$")
    val UUID_PATTERN = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", RegexOption.IGNORE_CASE)
}

/** Reserved events, see services/ingest/docs/protocol.md. */
internal object Events {
    const val IDENTIFY = "corlex.identify"
    const val USER_PROPS = "corlex.user_props"
    const val EXPOSURE = "corlex.exposure"
    const val CRASH = "corlex.crash"
}
