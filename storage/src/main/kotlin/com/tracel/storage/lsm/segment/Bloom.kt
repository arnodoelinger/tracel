package com.tracel.storage.lsm.segment

import com.tracel.storage.ffm.Bytes.i32
import com.tracel.storage.ffm.Bytes.i64
import java.io.OutputStream
import java.lang.foreign.MemorySegment
import java.lang.invoke.MethodHandles
import java.lang.invoke.VarHandle
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Builds the blocked bloom filter [Bloom] reads. */
class BloomBuilder(expectedEntries: Int) {
    private var hashes = LongArray(expectedEntries.coerceIn(16, 1 shl 16))
    private var count = 0
    private var blocks = 1
    private var words = LongArray(0)

    /** Adds a key to the filter. */
    fun add(key: ByteArray, length: Int) {
        if (count == hashes.size) hashes = hashes.copyOf(count + (count shr 1))
        hashes[count++] = Bloom.hash(key, length)
    }

    /**
     * Writes the filter to [out] and returns the number of bytes written:
     * none at all without a key.
     */
    fun writeTo(out: OutputStream): Long {
        if (count == 0) return 0L
        blocks = ((count.toLong() * Bloom.BITS_PER_KEY + 511) / 512).coerceIn(1L, (Int.MAX_VALUE shr 3).toLong()).toInt()
        words = LongArray(blocks shl 3)
        for (i in 0 until count) set(hashes[i])
        val buffer = ByteBuffer.allocate(8 + words.size * 8).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(blocks shl 9)
        buffer.putInt(Bloom.LANES)
        for (word in words) buffer.putLong(word)
        out.write(buffer.array())
        return buffer.capacity().toLong()
    }

    private fun set(hash: Long) {
        val base = (((hash ushr 32) * blocks) ushr 32).toInt() shl 3
        val w = words
        w[base] = w[base] or (1L shl ((hash * Bloom.SALT_0) ushr 58).toInt())
        w[base + 1] = w[base + 1] or (1L shl ((hash * Bloom.SALT_1) ushr 58).toInt())
        w[base + 2] = w[base + 2] or (1L shl ((hash * Bloom.SALT_2) ushr 58).toInt())
        w[base + 3] = w[base + 3] or (1L shl ((hash * Bloom.SALT_3) ushr 58).toInt())
        w[base + 4] = w[base + 4] or (1L shl ((hash * Bloom.SALT_4) ushr 58).toInt())
        w[base + 5] = w[base + 5] or (1L shl ((hash * Bloom.SALT_5) ushr 58).toInt())
        w[base + 6] = w[base + 6] or (1L shl ((hash * Bloom.SALT_6) ushr 58).toInt())
        w[base + 7] = w[base + 7] or (1L shl ((hash * Bloom.SALT_7) ushr 58).toInt())
    }
}

/** A blocked bloom filter over the segment's user keys. */
class Bloom internal constructor(
    private val segment: MemorySegment,
    offset: Long,
) {
    private val bitCount = segment.i32(offset)
    private val bits = offset + 8
    private val blocks = (bitCount ushr 9).toLong()

    fun mightContain(userKey: ByteArray): Boolean {
        if (bitCount == 0) return true
        val hash = hash(userKey, userKey.size)
        val base = bits + ((((hash ushr 32) * blocks) ushr 32) shl 6)
        val data = segment
        var missing = 0L
        missing = missing or ((1L shl ((hash * SALT_0) ushr 58).toInt()) and data.i64(base).inv())
        missing = missing or ((1L shl ((hash * SALT_1) ushr 58).toInt()) and data.i64(base + 8).inv())
        missing = missing or ((1L shl ((hash * SALT_2) ushr 58).toInt()) and data.i64(base + 16).inv())
        missing = missing or ((1L shl ((hash * SALT_3) ushr 58).toInt()) and data.i64(base + 24).inv())
        missing = missing or ((1L shl ((hash * SALT_4) ushr 58).toInt()) and data.i64(base + 32).inv())
        missing = missing or ((1L shl ((hash * SALT_5) ushr 58).toInt()) and data.i64(base + 40).inv())
        missing = missing or ((1L shl ((hash * SALT_6) ushr 58).toInt()) and data.i64(base + 48).inv())
        missing = missing or ((1L shl ((hash * SALT_7) ushr 58).toInt()) and data.i64(base + 56).inv())
        return missing == 0L
    }

    companion object {
        const val LANES = 8
        const val BITS_PER_KEY = 12

        private val LE_LONG: VarHandle = MethodHandles.byteArrayViewVarHandle(LongArray::class.java, ByteOrder.LITTLE_ENDIAN)
        private val LE_INT: VarHandle = MethodHandles.byteArrayViewVarHandle(IntArray::class.java, ByteOrder.LITTLE_ENDIAN)

        // Odd, but beautiful
        internal val SALT_0: Long = 0x9E3779B97F4A7C15uL.toLong()
        internal val SALT_1: Long = 0xC2B2AE3D27D4EB4FuL.toLong()
        internal val SALT_2: Long = 0x165667B19E3779F9uL.toLong()
        internal val SALT_3: Long = 0x85EBCA77C2B2AE63uL.toLong()
        internal val SALT_4: Long = 0x27D4EB2F165667C5uL.toLong()
        internal val SALT_5: Long = 0xD6E8FEB86659FD93uL.toLong()
        internal val SALT_6: Long = 0xA24BAED4963EE407uL.toLong()
        internal val SALT_7: Long = 0x9FB21C651E98DF25uL.toLong()

        private val SECRET_0: Long = 0x2D358DCCAA6C78A5uL.toLong()
        private val SECRET_1: Long = 0x8BB84B93962EACC9uL.toLong()
        private val SECRET_2: Long = 0x4B33A62ED433D4A3uL.toLong()

        fun hash(key: ByteArray, length: Int): Long {
            var seed = SECRET_0 xor length.toLong()
            var at = 0
            var remaining = length
            while (remaining >= 16) {
                seed = mix(le64(key, at) xor SECRET_1, le64(key, at + 8) xor seed)
                at += 16
                remaining -= 16
            }
            val a: Long
            val b: Long
            when {
                remaining >= 8 -> {
                    a = le64(key, at)
                    b = le64(key, length - 8)
                }

                remaining >= 4 -> {
                    a = le32(key, at)
                    b = le32(key, length - 4)
                }

                remaining >= 1 -> {
                    a = ((key[at].toLong() and 0xFF) shl 16) or
                            ((key[at + (remaining shr 1)].toLong() and 0xFF) shl 8) or
                            (key[length - 1].toLong() and 0xFF)
                    b = 0
                }

                else -> {
                    a = 0
                    b = 0
                }
            }
            return mix(SECRET_2 xor length.toLong(), mix(a xor SECRET_1, b xor seed))
        }

        private fun mix(a: Long, b: Long): Long = Math.unsignedMultiplyHigh(a, b) xor (a * b)

        private fun le64(key: ByteArray, at: Int): Long = LE_LONG.get(key, at) as Long

        private fun le32(key: ByteArray, at: Int): Long = (LE_INT.get(key, at) as Int).toLong() and 0xFFFFFFFFL
    }
}
