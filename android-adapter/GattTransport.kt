package dev.julianzhu.blekit.android

// Reference adapter, not part of the Gradle build (it needs the Android SDK).
// Drop it into an Android library module that depends on :connection.

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import dev.julianzhu.blekit.connection.BleSession
import dev.julianzhu.blekit.connection.BleTransport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class GattException(val status: Int, op: String) : Exception("$op failed, status=$status")

/**
 * BluetoothGatt only allows one outstanding operation at a time and reports
 * results on a binder thread. This adapter turns each operation into a
 * suspend call guarded by a Mutex, and the notification callback into a Flow.
 * Permissions (BLUETOOTH_CONNECT) are the caller's responsibility.
 */
@SuppressLint("MissingPermission")
class GattTransport(
    private val context: Context,
    private val device: BluetoothDevice,
    private val serviceUuid: UUID,
    private val rxUuid: UUID, // notify: device -> phone
    private val txUuid: UUID, // write: phone -> device
    private val requestMtu: Int = 247,
) : BleTransport {

    override suspend fun connect(): BleSession {
        val session = GattSession()
        val gatt = device.connectGatt(context, false, session.callback, BluetoothDevice.TRANSPORT_LE)
        session.gatt = gatt
        try {
            session.connected.await()
            session.op { gatt.discoverServices() }
            session.op { gatt.requestMtu(requestMtu) }
            val service = gatt.getService(serviceUuid) ?: throw IllegalStateException("service missing")
            val rx = service.getCharacteristic(rxUuid)
            session.tx = service.getCharacteristic(txUuid)
            gatt.setCharacteristicNotification(rx, true)
            val cccd = rx.getDescriptor(CCCD)
            session.op { gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothGatt.GATT_SUCCESS }
            return session
        } catch (t: Throwable) {
            // Includes cancellation and timeout from the manager: never leak a GATT client.
            gatt.close()
            throw t
        }
    }

    private class GattSession : BleSession {
        lateinit var gatt: BluetoothGatt
        var tx: BluetoothGattCharacteristic? = null
        val connected = CompletableDeferred<Unit>()
        private val dropped = CompletableDeferred<Throwable?>()
        private val mutex = Mutex()
        @Volatile private var pending: kotlinx.coroutines.CancellableContinuation<Unit>? = null

        private val _notifications = MutableSharedFlow<ByteArray>(
            extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
        override val notifications: Flow<ByteArray> = _notifications.asSharedFlow()

        /** Runs one GATT operation and suspends until its callback fires. */
        suspend fun op(start: () -> Boolean) = mutex.withLock {
            suspendCancellableCoroutine { cont ->
                pending = cont
                if (!start()) {
                    pending = null
                    cont.resumeWithException(GattException(-1, "start"))
                }
            }
        }

        private fun complete(status: Int, name: String) {
            val cont = pending ?: return
            pending = null
            if (status == BluetoothGatt.GATT_SUCCESS) cont.resume(Unit)
            else cont.resumeWithException(GattException(status, name))
        }

        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                when {
                    newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS ->
                        connected.complete(Unit)
                    else -> {
                        val err = GattException(status, "connection")
                        connected.completeExceptionally(err)
                        pending?.let { pending = null; it.resumeWithException(err) }
                        dropped.complete(if (status == BluetoothGatt.GATT_SUCCESS) null else err)
                    }
                }
            }
            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) = complete(status, "discover")
            override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) = complete(status, "mtu")
            override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) = complete(status, "cccd")
            override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) = complete(status, "write")
            override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
                _notifications.tryEmit(value)
            }
        }

        override suspend fun write(data: ByteArray) {
            val c = tx ?: error("not connected")
            op {
                gatt.writeCharacteristic(c, data, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
                    BluetoothGatt.GATT_SUCCESS
            }
        }

        override suspend fun awaitDisconnect(): Throwable? = dropped.await()

        override suspend fun close() {
            gatt.disconnect()
            gatt.close()
        }
    }

    private companion object {
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
