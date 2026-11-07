package com.tracel.storage.chrono.bits

/**
 * Writes runs of fixed-width numbers back to back, the first one in the lowest
 * bits of the first byte.
 */
object BitPack {
    /** Appends the low [bits] of the first [n] of [values]. */
    fun ints(sink: ByteWriter, values: IntArray, n: Int, bits: Int) {
        require(bits in 0..32) { "an int is 32 bits, not $bits." }
        pack(sink, n, bits) { values[it].toLong() }
    }

    /** Appends the low [bits] of the first [n] of [values]. */
    fun longs(sink: ByteWriter, values: LongArray, n: Int, bits: Int) {
        require(bits in 0..64) { "a long is 64 bits, not $bits." }
        pack(sink, n, bits) { values[it] }
    }

    /**
     * Appends [n] values as (value - [floor]), each [bits] wide.
     *
     * Frame of reference: a column whose numbers all sit near some floor is stored
     * as its distances from it.
     */
    fun framed(sink: ByteWriter, values: LongArray, n: Int, floor: Long, bits: Int) {
        require(bits in 0..64) { "a long is 64 bits, not $bits." }
        pack(sink, n, bits) { values[it] - floor }
    }

    private inline fun pack(sink: ByteWriter, n: Int, bits: Int, value: (Int) -> Long) {
        if (bits == 0 || n == 0) return
        val bytes = packedBytes(n, bits)
        sink.ensure(bytes + 8)
        val buf = sink.raw()
        val mask = lowMask(bits)
        val start = sink.size
        var at = start
        var acc = 0L
        var fill = 0
        for (i in 0 until n) {
            val v = value(i) and mask
            acc = acc or (v shl fill)
            val next = fill + bits
            if (next >= 64) {
                LittleEndian.LONG.set(buf, at, acc)
                at += 8
                acc = if (fill == 0) 0L else v ushr (64 - fill) // Word must not shift by 64
                fill = next - 64
            } else {
                fill = next
            }
        }
        while (fill > 0) {
            buf[at++] = acc.toByte()
            acc = acc ushr 8
            fill -= 8
        }
        check(at - start == bytes) { "packed ${at - start} bytes, meant $bytes." }
        sink.advance(bytes)
    }
}
