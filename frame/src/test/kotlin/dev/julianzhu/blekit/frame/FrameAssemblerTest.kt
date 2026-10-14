package dev.julianzhu.blekit.frame

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FrameAssemblerTest {
    private var now = 0L
    private val assembler = FrameAssembler(timeoutMillis = 1_000, maxPending = 2, clock = { now })

    private fun packets(seq: Int, msg: ByteArray, mtu: Int = 23) =
        Frame.fragment(seq, msg, mtu).map { it.encode() }

    @Test
    fun `in order fragments complete`() {
        val msg = Random(1).nextBytes(100)
        val events = packets(1, msg).map(assembler::accept)
        events.dropLast(1).forEach { assertIs<AssemblyEvent.Progress>(it) }
        assertContentEquals(msg, assertIs<AssemblyEvent.Complete>(events.last()).data)
        assertEquals(0, assembler.pendingCount)
    }

    @Test
    fun `out of order fragments complete`() {
        val msg = Random(2).nextBytes(300)
        val shuffled = packets(5, msg).shuffled(Random(2))
        val last = shuffled.map(assembler::accept).last()
        assertContentEquals(msg, assertIs<AssemblyEvent.Complete>(last).data)
    }

    @Test
    fun `duplicates are reported and ignored`() {
        val p = packets(3, ByteArray(30) { it.toByte() })
        assembler.accept(p[0])
        val dup = assertIs<AssemblyEvent.Duplicate>(assembler.accept(p[0]))
        assertEquals(0, dup.index)
        p.drop(1).dropLast(1).forEach { assembler.accept(it) }
        assertIs<AssemblyEvent.Complete>(assembler.accept(p.last()))
    }

    @Test
    fun `crc failure is rejected and does not poison the message`() {
        val msg = Random(4).nextBytes(40)
        val p = packets(4, msg)
        val corrupt = p[1].copyOf().also { it[8] = (it[8] + 1).toByte() }
        assertIs<AssemblyEvent.Rejected>(assembler.accept(corrupt))
        val last = p.map(assembler::accept).last()
        assertContentEquals(msg, assertIs<AssemblyEvent.Complete>(last).data)
    }

    @Test
    fun `stale partial is evicted with its gaps`() {
        val p = packets(7, ByteArray(50))
        assembler.accept(p[0])
        assembler.accept(p[2])
        now += 1_000
        val evicted = assembler.evictExpired()
        assertEquals(listOf(AssemblyEvent.Evicted(7, (0 until p.size).filter { it != 0 && it != 2 })), evicted)
        assertEquals(0, assembler.pendingCount)
    }

    @Test
    fun `eviction is reported on the next accept`() {
        val a = packets(1, ByteArray(40))
        assembler.accept(a[0])
        now += 5_000
        val ev = assembler.accept(packets(2, byteArrayOf(9)).single())
        assertIs<AssemblyEvent.Complete>(ev)
        assertEquals(1, ev.evicted.single().seq)
    }

    @Test
    fun `activity keeps a partial alive`() {
        val p = packets(8, ByteArray(60))
        p.dropLast(1).forEach {
            now += 900
            assembler.accept(it)
        }
        now += 900
        assertIs<AssemblyEvent.Complete>(assembler.accept(p.last()))
    }

    @Test
    fun `max pending evicts least recently updated`() {
        val a = packets(1, ByteArray(40))
        val b = packets(2, ByteArray(40))
        val c = packets(3, ByteArray(40))
        assembler.accept(a[0])
        assembler.accept(b[0])
        val ev = assembler.accept(c[0])
        assertEquals(listOf(1), ev.evicted.map { it.seq })
        assertEquals(2, assembler.pendingCount)
    }

    @Test
    fun `interleaved messages are kept apart`() {
        val m1 = Random(10).nextBytes(60)
        val m2 = Random(11).nextBytes(60)
        val p1 = packets(1, m1)
        val p2 = packets(2, m2)
        val done = p1.zip(p2).flatMap { (x, y) -> listOf(assembler.accept(x), assembler.accept(y)) }
            .filterIsInstance<AssemblyEvent.Complete>()
        assertEquals(listOf(1, 2), done.map { it.seq })
        assertContentEquals(m1, done[0].data)
        assertContentEquals(m2, done[1].data)
    }

    @Test
    fun `seq reuse with different total restarts the message`() {
        assembler.accept(packets(1, ByteArray(40))[0])
        val fresh = packets(1, ByteArray(20) { 1 })
        val last = fresh.map(assembler::accept).last()
        assertTrue(assertIs<AssemblyEvent.Complete>(last).data.all { it == 1.toByte() })
    }
}
