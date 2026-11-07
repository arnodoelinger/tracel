package com.tracel.storage.lsm.table

import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Builds the blocked bloom filter [BloomFilter] reads. */
class BloomFilterBuilder(expectedEntries: Int) {
    private var hashes = LongArray(expectedEntries.coerceIn(16, 1 shl 16))
    private var count = 0
    private var blocks = 1
    private var words = LongArray(0)

    /** Adds a key to the filter. */
    fun add(key: ByteArray, length: Int) {
        if (count == hashes.size) hashes = hashes.copyOf(count + (count shr 1))
        hashes[count++] = BloomFilter.hash(key, length)
    }

    /**
     * Writes the filter to [out] and returns the number of bytes written:
     * none at all without a key.
     */
    fun writeTo(out: OutputStream): Long {
        if (count == 0) return 0L
        blocks = ((count.toLong() * BloomFilter.BITS_PER_KEY + 511) / 512).coerceIn(1L, (Int.MAX_VALUE shr 3).toLong())
            .toInt()
        words = LongArray(blocks shl 3)
        for (i in 0 until count) set(hashes[i])
        val buffer = ByteBuffer.allocate(8 + words.size * 8).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(blocks shl 9)
        buffer.putInt(BloomFilter.LANES)
        for (word in words) buffer.putLong(word)
        out.write(buffer.array())
        return buffer.capacity().toLong()
    }

    private fun set(hash: Long) {
        val base = (((hash ushr 32) * blocks) ushr 32).toInt() shl 3
        val w = words
        w[base] = w[base] or (1L shl ((hash * BloomFilter.SALT_0) ushr 58).toInt())
        w[base + 1] = w[base + 1] or (1L shl ((hash * BloomFilter.SALT_1) ushr 58).toInt())
        w[base + 2] = w[base + 2] or (1L shl ((hash * BloomFilter.SALT_2) ushr 58).toInt())
        w[base + 3] = w[base + 3] or (1L shl ((hash * BloomFilter.SALT_3) ushr 58).toInt())
        w[base + 4] = w[base + 4] or (1L shl ((hash * BloomFilter.SALT_4) ushr 58).toInt())
        w[base + 5] = w[base + 5] or (1L shl ((hash * BloomFilter.SALT_5) ushr 58).toInt())
        w[base + 6] = w[base + 6] or (1L shl ((hash * BloomFilter.SALT_6) ushr 58).toInt())
        w[base + 7] = w[base + 7] or (1L shl ((hash * BloomFilter.SALT_7) ushr 58).toInt())
    }
}
