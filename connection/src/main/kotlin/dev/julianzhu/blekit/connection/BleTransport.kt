package dev.julianzhu.blekit.connection

import kotlinx.coroutines.flow.Flow

/**
 * The seam between connection policy and the platform BLE stack. The Android
 * implementation lives in `android-adapter/GattTransport.kt`; tests use a fake.
 */
fun interface BleTransport {
    /**
     * Establishes a link, including service discovery and any MTU / notification
     * setup the device needs. Throws on failure. Must be cancellable: if the
     * caller is cancelled mid-connect, the implementation releases the handle.
     */
    suspend fun connect(): BleSession
}

interface BleSession {
    /** Raw notification payloads, in arrival order. */
    val notifications: Flow<ByteArray>

    suspend fun write(data: ByteArray)

    /** Suspends until the link drops. Returns the cause, or null for a clean remote close. */
    suspend fun awaitDisconnect(): Throwable?

    /** Releases the link. Idempotent; called from a non-cancellable context. */
    suspend fun close()
}
