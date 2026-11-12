package ru.corlex.internal

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import kotlin.math.min
import kotlin.random.Random

/** What one request told the sender to do with a batch. Same policy as packages/sdk-core/src/sender.ts. */
internal sealed interface Outcome {
    data object Ok : Outcome
    data class Drop(val status: Int) : Outcome
    data object Split : Outcome
    data class Halt(val status: Int) : Outcome
    data class Retry(val retryAfterMs: Long?) : Outcome
}

internal object Backoff {
    private const val BASE_MS = 1000L
    private const val MAX_MS = 60_000L
    private const val RETRY_AFTER_MAX_MS = 5 * 60_000L

    /** `Retry-After` as milliseconds; only the seconds form is used. */
    fun parseRetryAfter(value: String?): Long? {
        val trimmed = value?.trim() ?: return null
        if (trimmed.isEmpty() || !trimmed.all { it in '0'..'9' }) return null
        val seconds = trimmed.toLongOrNull() ?: return RETRY_AFTER_MAX_MS
        return min(seconds * 1000, RETRY_AFTER_MAX_MS)
    }

    /** Exponential backoff with equal jitter: half fixed, half random. */
    fun delay(attempt: Int, random: Random = Random): Long {
        val ceiling = min(BASE_MS shl (attempt - 1).coerceIn(0, 10), MAX_MS)
        return ceiling / 2 + (random.nextDouble() * ceiling / 2).toLong()
    }

    fun classify(status: Int, retryAfter: String?, count: Int): Outcome = when {
        status in 200..299 -> Outcome.Ok
        status == 401 || status == 403 -> Outcome.Halt(status)
        status == 413 -> if (count > 1) Outcome.Split else Outcome.Drop(status)
        status in 300..399 || status == 429 || status >= 500 -> Outcome.Retry(parseRetryAfter(retryAfter))
        else -> Outcome.Drop(status)
    }
}

/** How a [Sender.drain] ended. */
internal sealed interface DrainResult {
    /** The queue is empty (or sending is stopped). */
    data object Done : DrainResult

    /** Delivery failed for real; try again in [delayMs]. */
    data class Failed(val delayMs: Long) : DrainResult
}

internal class SenderConfig(val endpoint: String, val key: String, val batchSize: Int) {
    var epoch: () -> Long = { 0 }
    var allowed: () -> Boolean = { true }
}

/**
 * Delivers the queue to `POST /v1/batch`. [drain] is synchronous and must not run on the main
 * thread; the client runs it on its own executor and WorkManager runs it in a worker.
 * Events leave the queue only after a 2xx or a final 4xx, and a retry sends the same `event_id`s.
 */
internal class Sender(
    private val queue: EventQueue,
    private val transport: Transport,
    private val config: SenderConfig,
    private val log: (String) -> Unit,
) {
    private var attempts = 0
    private var limit = config.batchSize

    /** Set after a 401/403: the key is wrong, so nothing more is sent and the queue is cleared. */
    @Volatile var rejected = false
        private set

    @Synchronized
    fun drain(): DrainResult {
        val epoch = config.epoch()
        fun current() = config.allowed() && config.epoch() == epoch
        while (current() && !rejected && queue.size() > 0) {
            val batch = queue.peek(limit)
            val outcome = post(batch)
            if (!current()) return DrainResult.Done
            when (outcome) {
                Outcome.Ok -> {
                    attempts = 0
                    limit = min(limit * 2, config.batchSize)
                    queue.drop(batch)
                }
                is Outcome.Drop -> {
                    log("dropping ${batch.size} event(s): server answered ${outcome.status}")
                    queue.drop(batch)
                }
                Outcome.Split -> limit = maxOf(1, batch.size / 2)
                is Outcome.Halt -> {
                    log("key rejected (${outcome.status}); events are not sent")
                    rejected = true
                    queue.clear()
                }
                is Outcome.Retry -> return failed(outcome.retryAfterMs)
            }
        }
        return DrainResult.Done
    }

    private fun failed(retryAfterMs: Long?): DrainResult {
        attempts += 1
        val delay = maxOf(Backoff.delay(attempts), retryAfterMs ?: 0)
        log("delivery failed, retry in $delay ms (attempt $attempts)")
        return DrainResult.Failed(delay)
    }

    private fun post(batch: List<JSONObject>): Outcome {
        val body = JSONObject().put("events", JSONArray(batch)).toString()
        return try {
            val res = transport.postBatch(config.endpoint, config.key, body)
            Backoff.classify(res.status, res.retryAfter, batch.size)
        } catch (_: IOException) {
            log("request_failed")
            Outcome.Retry(null)
        } catch (_: SecurityException) {
            log("request_failed")
            Outcome.Retry(null)
        }
    }
}
