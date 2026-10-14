package dev.julianzhu.blekit.frame

import dev.julianzhu.blekit.crc.Crc16

/**
 * One BLE notification on the wire:
 *
 * ```
 * offset  size  field
 * 0       2     magic   0xB1 0xE5
 * 2       2     seq     message id, big-endian, wraps at 0xFFFF
 * 4       1     total   number of fragments in the message (1..255)
 * 5       1     index   0-based fragment index
 * 6       1     len     payload length
 * 7       len   payload
 * 7+len   2     crc16   CRC-16/CCITT-FALSE over bytes [0, 7+len), big-endian
 * ```
 *
 * `len` is explicit even though the notification length is known, because some
 * stacks pad notifications and because it lets us reject truncated packets early.
 */
class Frame(
    val seq: Int,
    val total: Int,
    val index: Int,
    val payload: ByteArray,
) {
    init {
        require(seq in 0..0xFFFF) { "seq out of range: $seq" }
        require(total in 1..MAX_FRAGMENTS) { "total out of range: $total" }
        require(index in 0 until total) { "index $index not in 0 until $total" }
        require(payload.size <= MAX_PAYLOAD) { "payload too large: ${payload.size}" }
    }

    fun encode(): ByteArray {
        val out = ByteArray(HEADER_SIZE + payload.size + CRC_SIZE)
        out[0] = MAGIC_0
        out[1] = MAGIC_1
        out[2] = (seq ushr 8).toByte()
        out[3] = seq.toByte()
        out[4] = total.toByte()
        out[5] = index.toByte()
        out[6] = payload.size.toByte()
        payload.copyInto(out, HEADER_SIZE)
        val crc = Crc16.compute(out, 0, HEADER_SIZE + payload.size)
        out[out.size - 2] = (crc ushr 8).toByte()
        out[out.size - 1] = crc.toByte()
        return out
    }

    override fun toString() = "Frame(seq=$seq, $index/$total, ${payload.size}B)"

    companion object {
        const val HEADER_SIZE = 7
        const val CRC_SIZE = 2
        const val OVERHEAD = HEADER_SIZE + CRC_SIZE
        const val MAX_FRAGMENTS = 255
        const val MAX_PAYLOAD = 255
        const val MAGIC_0: Byte = 0xB1.toByte()
        const val MAGIC_1: Byte = 0xE5.toByte()

        /** Parses a raw notification. Never throws on bad input. */
        fun decode(raw: ByteArray): DecodeResult {
            if (raw.size < OVERHEAD) return DecodeResult.Malformed("too short: ${raw.size}B")
            if (raw[0] != MAGIC_0 || raw[1] != MAGIC_1) return DecodeResult.Malformed("bad magic")
            val len = raw[6].u8
            if (raw.size < OVERHEAD + len) return DecodeResult.Malformed("truncated: len=$len, got ${raw.size}B")
            val expected = (raw[HEADER_SIZE + len].u8 shl 8) or raw[HEADER_SIZE + len + 1].u8
            val actual = Crc16.compute(raw, 0, HEADER_SIZE + len)
            if (expected != actual) return DecodeResult.CrcMismatch(expected, actual)
            val seq = (raw[2].u8 shl 8) or raw[3].u8
            val total = raw[4].u8
            val index = raw[5].u8
            if (total == 0 || index >= total) return DecodeResult.Malformed("bad index $index/$total")
            return DecodeResult.Ok(Frame(seq, total, index, raw.copyOfRange(HEADER_SIZE, HEADER_SIZE + len)))
        }

        /**
         * Splits [message] into frames that each fit in one notification of [mtu]
         * bytes. ATT notifications carry MTU - 3 bytes of value.
         */
        fun fragment(seq: Int, message: ByteArray, mtu: Int): List<Frame> {
            val maxPayload = minOf(mtu - 3 - OVERHEAD, MAX_PAYLOAD)
            require(maxPayload > 0) { "MTU $mtu too small for framing" }
            val total = maxOf(1, (message.size + maxPayload - 1) / maxPayload)
            require(total <= MAX_FRAGMENTS) { "message needs $total fragments, max is $MAX_FRAGMENTS" }
            return List(total) { i ->
                val from = i * maxPayload
                Frame(seq, total, i, message.copyOfRange(from, minOf(from + maxPayload, message.size)))
            }
        }
    }
}

sealed interface DecodeResult {
    data class Ok(val frame: Frame) : DecodeResult
    data class Malformed(val reason: String) : DecodeResult
    data class CrcMismatch(val expected: Int, val actual: Int) : DecodeResult
}

internal val Byte.u8: Int get() = toInt() and 0xFF
