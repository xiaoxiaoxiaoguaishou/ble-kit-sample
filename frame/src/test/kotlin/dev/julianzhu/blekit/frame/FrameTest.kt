package dev.julianzhu.blekit.frame

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class FrameTest {
    @Test
    fun `encode then decode round trips`() {
        val f = Frame(seq = 0xABCD, total = 3, index = 2, payload = byteArrayOf(1, 2, 3))
        val raw = f.encode()
        assertEquals(Frame.OVERHEAD + 3, raw.size)
        val back = assertIs<DecodeResult.Ok>(Frame.decode(raw)).frame
        assertEquals(0xABCD, back.seq)
        assertEquals(3, back.total)
        assertEquals(2, back.index)
        assertContentEquals(byteArrayOf(1, 2, 3), back.payload)
    }

    @Test
    fun `wire layout is stable`() {
        val raw = Frame(seq = 1, total = 1, index = 0, payload = byteArrayOf(0x42)).encode()
        assertContentEquals(
            byteArrayOf(0xB1.toByte(), 0xE5.toByte(), 0, 1, 1, 0, 1, 0x42),
            raw.copyOfRange(0, 8),
        )
    }

    @Test
    fun `any single bit flip is detected`() {
        val raw = Frame(7, 1, 0, Random(1).nextBytes(20)).encode()
        for (byte in 7 until raw.size) for (bit in 0 until 8) {
            val bad = raw.copyOf().also { it[byte] = (it[byte].toInt() xor (1 shl bit)).toByte() }
            val r = Frame.decode(bad)
            check(r !is DecodeResult.Ok) { "flip at $byte:$bit not detected" }
        }
    }

    @Test
    fun `malformed input is rejected without throwing`() {
        assertIs<DecodeResult.Malformed>(Frame.decode(ByteArray(3)))
        assertIs<DecodeResult.Malformed>(Frame.decode(ByteArray(12)))
        val raw = Frame(1, 2, 0, ByteArray(10)).encode()
        assertIs<DecodeResult.Malformed>(Frame.decode(raw.copyOf(raw.size - 4)))
    }

    @Test
    fun `fragment respects mtu and preserves content`() {
        val msg = Random(3).nextBytes(1000)
        val frames = Frame.fragment(seq = 9, message = msg, mtu = 185)
        frames.forEach { assert(it.encode().size <= 185 - 3) }
        assertEquals(frames.size, frames.first().total)
        assertContentEquals(msg, frames.flatMap { it.payload.toList() }.toByteArray())
    }

    @Test
    fun `empty message is one empty frame`() {
        val frames = Frame.fragment(1, ByteArray(0), 23)
        assertEquals(1, frames.size)
        assertEquals(0, frames[0].payload.size)
    }

    @Test
    fun `mtu too small is rejected`() {
        assertFailsWith<IllegalArgumentException> { Frame.fragment(1, ByteArray(4), 12) }
    }
}
