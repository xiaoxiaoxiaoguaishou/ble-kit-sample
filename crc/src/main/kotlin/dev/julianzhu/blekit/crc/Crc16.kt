package dev.julianzhu.blekit.crc

/**
 * CRC-16/CCITT-FALSE: poly 0x1021, init 0xFFFF, no reflection, no final XOR.
 *
 * This is the variant most BLE firmware stacks (and Nordic's DFU tooling) use,
 * so it is the default for frame integrity checks in this project.
 */
object Crc16 {
    private const val POLY = 0x1021
    const val INITIAL = 0xFFFF

    private val table = IntArray(256) { i ->
        var crc = i shl 8
        repeat(8) {
            crc = if (crc and 0x8000 != 0) (crc shl 1) xor POLY else crc shl 1
        }
        crc and 0xFFFF
    }

    /** Computes the CRC over [data] in the range [offset, offset + length). */
    fun compute(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): Int =
        update(INITIAL, data, offset, length)

    /** Continues a running CRC, so large payloads can be checked chunk by chunk. */
    fun update(crc: Int, data: ByteArray, offset: Int = 0, length: Int = data.size - offset): Int {
        require(offset >= 0 && length >= 0 && offset + length <= data.size) { "range out of bounds" }
        var c = crc and 0xFFFF
        for (i in offset until offset + length) {
            val idx = ((c ushr 8) xor data[i].toInt()) and 0xFF
            c = ((c shl 8) xor table[idx]) and 0xFFFF
        }
        return c
    }
}
