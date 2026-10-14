package dev.julianzhu.blekit.connection

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/** Scripted transport: each connect() consumes the next step. */
class FakeTransport(vararg steps: Step) : BleTransport {
    sealed interface Step {
        data class Fail(val error: Throwable = RuntimeException("gatt 133"), val afterMillis: Long = 0) : Step
        data class Succeed(val afterMillis: Long = 0) : Step
        data object Hang : Step
    }

    private val script = ArrayDeque(steps.toList())
    val sessions = mutableListOf<FakeSession>()
    var connectCalls = 0
        private set
    var cancelledConnects = 0
        private set

    override suspend fun connect(): BleSession {
        connectCalls++
        val step = script.removeFirstOrNull() ?: Step.Hang
        try {
            when (step) {
                is Step.Fail -> { delay(step.afterMillis); throw step.error }
                is Step.Succeed -> delay(step.afterMillis)
                Step.Hang -> awaitCancellation()
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            cancelledConnects++
            throw e
        }
        return FakeSession().also { sessions += it }
    }
}

class FakeSession : BleSession {
    private val dropped = CompletableDeferred<Throwable?>()
    var closed = 0
        private set

    override val notifications: Flow<ByteArray> = emptyFlow()
    override suspend fun write(data: ByteArray) = Unit
    override suspend fun awaitDisconnect(): Throwable? = dropped.await()
    override suspend fun close() { closed++ }

    fun drop(cause: Throwable? = RuntimeException("link loss")) { dropped.complete(cause) }
}
