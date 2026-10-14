package dev.julianzhu.blekit.connection

import kotlin.random.Random

/**
 * Exponential backoff with bounded jitter.
 *
 * Delay for retry n (0-based) is `min(maxDelay, initialDelay * multiplier^n)`, then
 * reduced by a random fraction up to [jitter]. Jitter matters more than it looks
 * for wearables: when a phone comes back into range of several devices at once,
 * un-jittered retries all hit the controller in the same window.
 */
data class ReconnectPolicy(
    val initialDelayMillis: Long = 500,
    val maxDelayMillis: Long = 30_000,
    val multiplier: Double = 2.0,
    val jitter: Double = 0.5,
    /** Consecutive failed connect attempts before giving up. Resets after a successful connect. */
    val maxAttempts: Int = 8,
    val connectTimeoutMillis: Long = 15_000,
) {
    init {
        require(initialDelayMillis >= 0 && maxDelayMillis >= initialDelayMillis)
        require(multiplier >= 1.0)
        require(jitter in 0.0..1.0)
        require(maxAttempts >= 1)
        require(connectTimeoutMillis > 0)
    }

    fun delayFor(retry: Int, random: Random): Long {
        var d = initialDelayMillis.toDouble()
        repeat(retry) { d = minOf(d * multiplier, maxDelayMillis.toDouble()) }
        val capped = minOf(d, maxDelayMillis.toDouble())
        return (capped * (1.0 - jitter * random.nextDouble())).toLong()
    }
}
