package io.github.ravenliao.svg2vd.upstream

import java.io.IOException
import java.time.Duration

internal data class GitilesResponse<T>(
    val statusCode: Int,
    val body: T,
    val headers: Map<String, List<String>> = emptyMap(),
)

internal fun <T> retryGitiles(
    resource: String,
    retryDelay: Duration,
    operation: () -> GitilesResponse<T>,
    failure: (String) -> Nothing,
): T {
    require(!retryDelay.isNegative) { "Gitiles retry delay must not be negative" }
    var attempt = 0
    var backoffIndex = 0
    while (true) {
        attempt += 1
        try {
            val response = operation()
            if (response.statusCode in 200..299) return response.body
            val status = response.statusCode
            if (status != 408 && status != 429 && status !in 500..599) {
                failure("Gitiles $resource returned HTTP $status")
            }
            if (attempt >= MAX_ATTEMPTS) {
                failure("Gitiles $resource returned HTTP $status after $attempt attempts")
            }
            sleepBeforeRetry(response.headers, retryDelay, backoffIndex++)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            failure("Gitiles $resource request interrupted")
        } catch (error: IOException) {
            if (attempt >= MAX_ATTEMPTS) {
                failure("Gitiles $resource request failed after $attempt attempts: ${error.message}")
            }
            sleepBeforeRetry(emptyMap(), retryDelay, backoffIndex++)
        }
    }
}

private fun sleepBeforeRetry(headers: Map<String, List<String>>, retryDelay: Duration, backoffIndex: Int) {
    val retryAfter = headers.entries
        .firstOrNull { it.key.equals("retry-after", ignoreCase = true) }
        ?.value
        ?.firstOrNull()
        ?.toLongOrNull()
        ?.takeIf { it >= 0 }
        ?.let { seconds ->
            if (seconds > MAX_RETRY_DELAY_MILLIS / 1000) MAX_RETRY_DELAY_MILLIS else seconds * 1000
        }
    val baseDelay = retryDelay.toMillis().coerceAtLeast(0)
    val factor = 1L shl backoffIndex
    val exponential = if (baseDelay > MAX_RETRY_DELAY_MILLIS / factor) {
        MAX_RETRY_DELAY_MILLIS
    } else {
        baseDelay * factor
    }
    val delay = (retryAfter ?: exponential).coerceAtMost(MAX_RETRY_DELAY_MILLIS)
    try {
        Thread.sleep(delay)
    } catch (error: InterruptedException) {
        Thread.currentThread().interrupt()
        throw InterruptedException("Gitiles retry interrupted")
    }
}

private const val MAX_ATTEMPTS = 5
private const val MAX_RETRY_DELAY_MILLIS = 30_000L
