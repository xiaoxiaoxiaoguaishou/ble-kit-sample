package dev.julianzhu.blekit.connection

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.random.Random

class ConnectTimeoutException(millis: Long) : Exception("connect timed out after ${millis}ms")

/**
 * Owns one logical connection to one device and keeps it alive.
 *
 * All state lives in a single coroutine per connect() call, so there are no
 * locks and no callback re-entrancy: the state machine is just the control
 * flow of [run]. Cancelling that coroutine (disconnect(), or cancelling [scope])
 * always ends in [ConnectionState.Disconnected] with the session closed.
 */
class BleConnectionManager(
    private val transport: BleTransport,
    private val scope: CoroutineScope,
    private val policy: ReconnectPolicy = ReconnectPolicy(),
    private val random: Random = Random.Default,
) {
    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private var job: Job? = null

    /** Starts connecting. No-op if already running; restarts after Failed. */
    fun connect() {
        if (job?.isActive == true) return
        job = scope.launch { run() }
    }

    /** Stops reconnecting, closes any open session and waits until that is done. */
    suspend fun disconnect() {
        job?.let {
            it.cancel()
            it.join()
        }
        job = null
    }

    private suspend fun run() {
        var session: BleSession? = null
        try {
            var failures = 0
            while (true) {
                _state.value = ConnectionState.Connecting(failures + 1)
                val result = runCatchingNonCancellation { connectWithTimeout() }
                val opened = result.getOrNull()
                if (opened == null) {
                    val cause = result.exceptionOrNull()!!
                    failures++
                    if (failures >= policy.maxAttempts) {
                        _state.value = ConnectionState.Failed(cause, failures)
                        return
                    }
                    backoff(retry = failures - 1, nextAttempt = failures + 1, cause = cause)
                    continue
                }

                session = opened
                failures = 0
                _state.value = ConnectionState.Connected(opened)
                val cause = opened.awaitDisconnect()
                closeQuietly(opened)
                session = null
                // A link that was up deserves a quick first retry: drops are usually transient.
                backoff(retry = 0, nextAttempt = 1, cause = cause)
            }
        } finally {
            session?.let { closeQuietly(it) }
            if (_state.value !is ConnectionState.Failed) _state.value = ConnectionState.Disconnected
        }
    }

    private suspend fun backoff(retry: Int, nextAttempt: Int, cause: Throwable?) {
        val wait = policy.delayFor(retry, random)
        _state.value = ConnectionState.Reconnecting(nextAttempt, wait, cause)
        delay(wait)
    }

    private suspend fun connectWithTimeout(): BleSession = try {
        withTimeout(policy.connectTimeoutMillis) { transport.connect() }
    } catch (e: TimeoutCancellationException) {
        // Re-type so a timeout counts as a failed attempt rather than cancellation.
        throw ConnectTimeoutException(policy.connectTimeoutMillis)
    }

    private suspend fun closeQuietly(session: BleSession) {
        withContext(NonCancellable) {
            runCatching { session.close() }
        }
    }
}

/** Like runCatching, but never swallows cancellation of the caller. */
private inline fun <T> runCatchingNonCancellation(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    Result.failure(e)
}
