package dev.julianzhu.blekit.crc

import java.util.zip.CRC32
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CrcTest {
    private val check = "123456789".toByteArray(Charsets.US_ASCII)

    @Test
    fun `crc16 ccitt-false check value`() {
        assertEquals(0x29B1, Crc16.compute(check))
    }

    @Test
    fun `crc16 of empty input is the initial value`() {
        assertEquals(0xFFFF, Crc16.compute(ByteArray(0)))
    }

    @Test
    fun `crc16 incremental matches one-shot`() {
        val partial = Crc16.update(Crc16.INITIAL, check, 0, 4)
        assertEquals(Crc16.compute(check), Crc16.update(partial, check, 4, 5))
    }

    @Test
    fun `crc16 respects offset and length`() {
        val padded = byteArrayOf(0x55, 0x55) + check + byteArrayOf(0x55)
        assertEquals(0x29B1, Crc16.compute(padded, 2, check.size))
    }

    @Test
    fun `crc32 check value`() {
        assertEquals(0xCBF43926L, Crc32.compute(check))
        assertEquals(0L, Crc32.compute(ByteArray(0)))
    }

    @Test
    fun `crc32 matches java util zip on random data`() {
        val rnd = Random(42)
        repeat(50) {
            val data = rnd.nextBytes(rnd.nextInt(0, 4096))
            val ref = CRC32().apply { update(data) }.value
            assertEquals(ref, Crc32.compute(data))
        }
    }

    @Test
    fun `crc32 streaming matches one-shot`() {
        val data = Random(7).nextBytes(10_000)
        val running = Crc32.Running()
        data.toList().chunked(244).forEach { running.update(it.toByteArray()) }
        assertEquals(Crc32.compute(data), running.value)
    }

    @Test
    fun `out of range is rejected`() {
        assertFailsWith<IllegalArgumentException> { Crc16.compute(check, 5, 10) }
        assertFailsWith<IllegalArgumentException> { Crc32.compute(check, -1, 2) }
    }
}
