package dev.julianzhu.blekit.crc

/**
 * CRC-32 (ISO-HDLC, same as zlib / java.util.zip.CRC32): reflected poly
 * 0xEDB88320, init and final XOR 0xFFFFFFFF.
 *
 * Used for whole-image verification after an OTA transfer. Values are returned
 * as an unsigned Long so they compare cleanly with what firmware reports.
 */
object Crc32 {
    private const val POLY = 0xEDB88320.toInt()

    private val table = IntArray(256) { i ->
        var crc = i
        repeat(8) {
            crc = if (crc and 1 != 0) (crc ushr 1) xor POLY else crc ushr 1
        }
        crc
    }

    fun compute(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): Long =
        Running().apply { update(data, offset, length) }.value

    /** Incremental form for streaming large images without buffering them. */
    class Running {
        private var crc = -1

        fun update(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): Running {
            require(offset >= 0 && length >= 0 && offset + length <= data.size) { "range out of bounds" }
            var c = crc
            for (i in offset until offset + length) {
                c = (c ushr 8) xor table[(c xor data[i].toInt()) and 0xFF]
            }
            crc = c
            return this
        }

        val value: Long get() = (crc.inv().toLong()) and 0xFFFFFFFFL

        fun reset() { crc = -1 }
    }
}
