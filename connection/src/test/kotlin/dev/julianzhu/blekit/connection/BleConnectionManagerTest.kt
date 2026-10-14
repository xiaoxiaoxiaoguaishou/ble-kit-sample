package dev.julianzhu.blekit.connection

import dev.julianzhu.blekit.connection.FakeTransport.Step
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class BleConnectionManagerTest {
    private val noJitter = ReconnectPolicy(
        initialDelayMillis = 1_000, maxDelayMillis = 8_000, jitter = 0.0,
        maxAttempts = 5, connectTimeoutMillis = 10_000,
    )

    // Managers run in the test scope itself (not backgroundScope) so that
    // advanceUntilIdle() drives them; each one is disconnected at the end so runTest can finish.
    private inner class Harness(private val scope: TestScope) {
        val created = mutableListOf<BleConnectionManager>()
        fun manager(t: BleTransport, policy: ReconnectPolicy = noJitter) =
            BleConnectionManager(t, scope, policy, Random(0)).also { created += it }
    }

    private fun test(body: suspend TestScope.(Harness) -> Unit) = runTest {
        val h = Harness(this)
        body(h)
        h.created.forEach { it.disconnect() }
    }

    private fun TestScope.record(m: BleConnectionManager): List<ConnectionState> {
        val seen = mutableListOf<ConnectionState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { m.state.collect { seen += it } }
        return seen
    }

    @Test
    fun `connects on first try`() = test { h ->
        val t = FakeTransport(Step.Succeed(afterMillis = 300))
        val m = h.manager(t)
        m.connect()
        advanceTimeBy(299); runCurrent()
        assertEquals(ConnectionState.Connecting(1), m.state.value)
        advanceTimeBy(1); runCurrent()
        assertIs<ConnectionState.Connected>(m.state.value)
    }

    @Test
    fun `backoff grows exponentially and is capped`() = test { h ->
        val t = FakeTransport(Step.Fail(), Step.Fail(), Step.Fail(), Step.Fail(), Step.Succeed())
        val m = h.manager(t)
        val seen = record(m)
        m.connect()
        advanceUntilIdle()
        val delays = seen.filterIsInstance<ConnectionState.Reconnecting>().map { it.delayMillis }
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L), delays)
        assertEquals(15_000L, currentTime)
        assertIs<ConnectionState.Connected>(m.state.value)
    }

    @Test
    fun `gives up after max attempts`() = test { h ->
        val t = FakeTransport(*Array(10) { Step.Fail() })
        val m = h.manager(t)
        m.connect()
        advanceUntilIdle()
        val failed = assertIs<ConnectionState.Failed>(m.state.value)
        assertEquals(5, failed.attempts)
        assertEquals(5, t.connectCalls)
    }

    @Test
    fun `connect after failure starts over`() = test { h ->
        val t = FakeTransport(Step.Fail(), Step.Succeed())
        val m = h.manager(t, noJitter.copy(maxAttempts = 1))
        m.connect(); advanceUntilIdle()
        assertIs<ConnectionState.Failed>(m.state.value)
        m.connect(); advanceUntilIdle()
        assertIs<ConnectionState.Connected>(m.state.value)
    }

    @Test
    fun `connect timeout counts as a failed attempt`() = test { h ->
        val t = FakeTransport(Step.Hang, Step.Succeed())
        val m = h.manager(t)
        val seen = record(m)
        m.connect()
        advanceUntilIdle()
        val r = seen.filterIsInstance<ConnectionState.Reconnecting>().single()
        assertIs<ConnectTimeoutException>(r.cause)
        assertEquals(1, t.cancelledConnects)
        assertEquals(11_000L, currentTime)
        assertIs<ConnectionState.Connected>(m.state.value)
    }

    @Test
    fun `link loss triggers reconnect and resets the failure count`() = test { h ->
        val t = FakeTransport(Step.Fail(), Step.Succeed(), Step.Fail(), Step.Succeed())
        val m = h.manager(t)
        val seen = record(m)
        m.connect(); advanceUntilIdle()
        t.sessions.single().drop()
        advanceUntilIdle()
        assertEquals(1, t.sessions[0].closed)
        assertIs<ConnectionState.Connected>(m.state.value)
        // Fail(1s) -> connect -> drop(1s) -> Fail(1s again, count was reset) -> connect
        val delays = seen.filterIsInstance<ConnectionState.Reconnecting>().map { it.delayMillis }
        assertEquals(listOf(1_000L, 1_000L, 1_000L), delays)
    }

    @Test
    fun `disconnect during backoff stops retries`() = test { h ->
        val t = FakeTransport(Step.Fail(), Step.Succeed())
        val m = h.manager(t)
        m.connect()
        runCurrent()
        assertIs<ConnectionState.Reconnecting>(m.state.value)
        m.disconnect()
        advanceUntilIdle()
        assertEquals(ConnectionState.Disconnected, m.state.value)
        assertEquals(1, t.connectCalls)
    }

    @Test
    fun `disconnect while connected closes the session exactly once`() = test { h ->
        val t = FakeTransport(Step.Succeed())
        val m = h.manager(t)
        m.connect(); runCurrent()
        m.disconnect()
        assertEquals(1, t.sessions.single().closed)
        assertEquals(ConnectionState.Disconnected, m.state.value)
    }

    @Test
    fun `disconnect mid connect cancels the transport call`() = test { h ->
        val t = FakeTransport(Step.Hang)
        val m = h.manager(t)
        m.connect(); runCurrent()
        m.disconnect()
        assertEquals(1, t.cancelledConnects)
        assertEquals(ConnectionState.Disconnected, m.state.value)
    }

    @Test
    fun `connect is idempotent while running`() = test { h ->
        val t = FakeTransport(Step.Succeed(afterMillis = 100))
        val m = h.manager(t)
        m.connect(); m.connect(); runCurrent(); m.connect()
        advanceUntilIdle()
        assertEquals(1, t.connectCalls)
    }

    @Test
    fun `jitter stays within bounds and varies`() {
        val p = ReconnectPolicy(initialDelayMillis = 1_000, maxDelayMillis = 30_000, jitter = 0.5)
        val rnd = Random(123)
        val samples = List(200) { p.delayFor(3, rnd) }
        assertTrue(samples.all { it in 4_000L..8_000L })
        assertTrue(samples.toSet().size > 50)
        assertEquals(30_000L, p.copy(jitter = 0.0).delayFor(40, rnd))
    }
}
