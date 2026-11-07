package com.tracel.storage.chrono.bits

/**
 * A growable little-endian byte buffer, which every encoder in here writes into.
 *
 * Not thread-safe.
 */
class ByteWriter(initial: Int = 256) {
    private var buf = ByteArray(maxOf(initial, 16))

    var size: Int = 0; private set

    fun u8(v: Int) {
        ensure(1)
        buf[size++] = v.toByte()
    }

    fun u16(v: Int) {
        ensure(2)
        LittleEndian.SHORT.set(buf, size, v.toShort())
        size += 2
    }

    fun u32(v: Int) {
        ensure(4)
        LittleEndian.INT.set(buf, size, v)
        size += 4
    }

    fun u64(v: Long) {
        ensure(8)
        LittleEndian.LONG.set(buf, size, v)
        size += 8
    }

    /** Unsigned LEB128: seven bits a byte, the high bit saying another follows. */
    fun varint(v: Long) {
        ensure(10)
        var rest = v
        while (rest and 0x7FL.inv() != 0L) {
            buf[size++] = ((rest and 0x7F) or 0x80).toByte()
            rest = rest ushr 7
        }
        buf[size++] = rest.toByte()
    }

    /** [varint] of an int read as unsigned, so -1 is five bytes and not ten. */
    fun varint(v: Int): Unit = varint(v.toLong() and 0xFFFF_FFFFL)

    /** [varint] of a signed number, through [zigzag]. */
    fun signed(v: Long): Unit = varint(zigzag(v))

    /** Writes [length] bytes from [src], starting at [from], into the buffer. */
    fun bytes(src: ByteArray, from: Int = 0, length: Int = src.size - from) {
        ensure(length)
        src.copyInto(buf, size, from, from + length)
        size += length
    }

    /** Rewrites four bytes already written at [at]. */
    fun patchU32(at: Int, v: Int) {
        require(at >= 0 && at + 4 <= size) { "patch at $at overruns the $size bytes written." }
        LittleEndian.INT.set(buf, at, v)
    }

    /** Room for [extra] more bytes, doubling so a long run of small writes stays linear. */
    fun ensure(extra: Int) {
        val need = size + extra
        if (need > buf.size) buf = buf.copyOf(maxOf(need, buf.size shl 1))
    }

    /** The bytes written, then [padding] zeroes a reader taking whole words at a time may run into. */
    fun toByteArray(padding: Int = 0): ByteArray = ByteArray(size + padding).also { buf.copyInto(it, 0, 0, size) }

    /** Clears it for another encode, keeping the buffer. */
    fun reset() {
        size = 0
    }

    /** The raw buffer, which may be bigger than [size] and is not guaranteed to stay the same across writes. */
    internal fun raw(): ByteArray = buf

    /** Advances the size by [n] bytes, which must have been written directly into [raw]. */
    internal fun advance(n: Int) {
        size += n
    }
}
