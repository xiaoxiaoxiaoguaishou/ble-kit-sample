package dev.julianzhu.blekit.connection

sealed interface ConnectionState {
    data object Disconnected : ConnectionState

    /** [attempt] is 1-based within the current run of consecutive failures. */
    data class Connecting(val attempt: Int) : ConnectionState

    data class Connected(val session: BleSession) : ConnectionState

    /** Waiting [delayMillis] before the next attempt, after [cause]. */
    data class Reconnecting(val nextAttempt: Int, val delayMillis: Long, val cause: Throwable?) : ConnectionState

    /** Gave up after [attempts] consecutive failures. Call connect() to start over. */
    data class Failed(val cause: Throwable, val attempts: Int) : ConnectionState
}
