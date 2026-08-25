package com.tracel.storage.lsm

import com.tracel.storage.ffm.Bytes.i32
import com.tracel.storage.ffm.Bytes.i64
import com.tracel.storage.ffm.Bytes.readBytes
import com.tracel.storage.ffm.MappedFile
import com.tracel.storage.ffm.SegmentCompare
import java.io.BufferedOutputStream
import java.io.OutputStream
import java.lang.foreign.MemorySegment
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path

/**
 * An immutable sorted run.
 *
 * ```
 * data      [u32 keyLen][i32 valueLen, -1 = deletion][internal key][value]    ... ascending
 * index     [u32 keyLen][internal key][u64 dataOffset]                        ... one per INDEX_STRIDE of data
 * bloom     [u32 bitCount][u32 hashCount][bits]                               ... over user keys
 * footer    40 bytes, at EOF
 * ```
 *
 * Written once, `fsync`ed, then never touched again.
 */
object SegmentFile {
    const val MAGIC = 0x54524C53
    const val VERSION = 1
    const val FOOTER_BYTES = 40
    private const val INDEX_STRIDE = 2 * 1024
//    private const val BLOOM_BITS_PER_KEY = 10
//    private const val BLOOM_HASHES = 6

    class Writer(private val path: Path, expectedEntries: Int) : AutoCloseable {
        private val file = java.io.FileOutputStream(path.toFile())
        private val out: OutputStream = BufferedOutputStream(file, 1 shl 16)
        private val indexKeys = ArrayList<ByteArray>()
        private val indexOffsets = ArrayList<Long>()
        private val bloom = BloomBuilder(expectedEntries.coerceAtLeast(1))

        private var dataBytes = 0L
        private var sinceIndex = 0L
        private var count = 0
        private var firstKey: ByteArray? = null
        private var lastKey: ByteArray? = null

        fun add(internalKey: ByteArray, value: ByteArray?) {
            if (sinceIndex == 0L || sinceIndex >= INDEX_STRIDE) {
                indexKeys += internalKey
                indexOffsets += dataBytes
                sinceIndex = 0
            }
            writeInt(internalKey.size)
            writeInt(value?.size ?: -1)
            out.write(internalKey)
            if (value != null) out.write(value)

            val entryBytes = 8L + internalKey.size + (value?.size ?: 0)
            dataBytes += entryBytes
            sinceIndex += entryBytes
            count++
            bloom.add(internalKey, internalKey.size - InternalKey.TRAILER_BYTES)
            if (firstKey == null) firstKey = internalKey
            lastKey = internalKey
        }

        fun finish(id: Long, level: Int): SegmentMeta {
            val indexOffset = dataBytes
            var indexBytes = 0L
            for (i in indexKeys.indices) {
                writeInt(indexKeys[i].size)
                out.write(indexKeys[i])
                writeLong(indexOffsets[i])
                indexBytes += 12L + indexKeys[i].size
            }

            val bloomOffset = indexOffset + indexBytes
            val bloomBytes = bloom.writeTo(out)

            val footer = ByteBuffer.allocate(FOOTER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
            footer.putLong(indexOffset)
            footer.putInt(indexBytes.toInt())
            footer.putLong(bloomOffset)
            footer.putInt(bloomBytes.toInt())
            footer.putLong(count.toLong())
            footer.putInt(VERSION)
            footer.putInt(MAGIC)
            out.write(footer.array())
            out.flush()
            file.fd.sync()
            file.close()

            return SegmentMeta(id, level, count, Files.size(path), firstKey ?: ByteArray(0), lastKey ?: ByteArray(0))
        }

        override fun close() {
            runCatching { out.close() }
            runCatching { file.close() }
        }

        private fun writeInt(value: Int) {
            out.write(value and 0xFF)
            out.write((value ushr 8) and 0xFF)
            out.write((value ushr 16) and 0xFF)
            out.write((value ushr 24) and 0xFF)
        }

        private fun writeLong(value: Long) {
            for (shift in 0 until 64 step 8) out.write(((value ushr shift) and 0xFF).toInt())
        }
    }

    fun open(path: Path, meta: SegmentMeta): SegmentReader {
        val mapped = MappedFile.openRead(path)
        val segment = mapped.segment
        val footerAt = segment.byteSize() - FOOTER_BYTES
        require(footerAt >= 0) { "$path is too short to be a segment" }
        val magic = segment.i32(footerAt + 36)
        require(magic == MAGIC) { "$path is not a Tracel segment (magic ${magic.toString(16)})" }
        val version = segment.i32(footerAt + 32)
        require(version == VERSION) { "$path was written by segment format v$version, this build reads v$VERSION" }

        val indexOffset = segment.i64(footerAt)
        val indexBytes = segment.i32(footerAt + 8)
        val bloomOffset = segment.i64(footerAt + 12)
        val bloomBytes = segment.i32(footerAt + 20)

        val entryOffsets = ArrayList<Int>()
        var at = indexOffset
        val indexEnd = indexOffset + indexBytes
        while (at < indexEnd) {
            entryOffsets += (at - indexOffset).toInt()
            at += 4 + segment.i32(at) + 8
        }

        return SegmentReader(
            mapped,
            meta,
            indexOffset,
            entryOffsets.toIntArray(),
            dataEnd = indexOffset,
            bloom = if (bloomBytes > 0) Bloom(segment, bloomOffset) else null,
        )
    }
}

data class SegmentMeta(
    val id: Long,
    val level: Int,
    val entries: Int,
    val fileBytes: Long,
    val firstKey: ByteArray,
    val lastKey: ByteArray,
) {
    override fun equals(other: Any?): Boolean = other is SegmentMeta && other.id == id && other.level == level

    override fun hashCode(): Int = id.hashCode() * 31 + level
}

class SegmentReader(
    private val mapped: MappedFile,
    val meta: SegmentMeta,
    private val indexOffset: Long,
    private val indexEntries: IntArray,
    private val dataEnd: Long,
    private val bloom: Bloom?,
) : AutoCloseable {
    val segment: MemorySegment get() = mapped.segment
    val path: Path get() = mapped.path

    fun seek(internalKey: ByteArray): Long {
        var low = 0
        var high = indexEntries.size - 1
        var block = 0L
        while (low <= high) {
            val mid = (low + high) ushr 1
            val entry = indexOffset + indexEntries[mid]
            val keyLength = segment.i32(entry)
            if (SegmentCompare.compare(segment, entry + 4, keyLength, internalKey) <= 0) {
                block = segment.i64(entry + 4 + keyLength)
                low = mid + 1
            } else {
                high = mid - 1
            }
        }

        var at = block
        while (at < dataEnd) {
            val keyLength = segment.i32(at)
            if (SegmentCompare.compare(segment, at + 8, keyLength, internalKey) >= 0) return at
            at += 8 + keyLength + valueBytes(at)
        }
        return dataEnd
    }

    fun mightContain(userKey: ByteArray): Boolean = bloom?.mightContain(userKey) ?: true

    fun end(): Long = dataEnd

    fun keyOffset(at: Long): Long = at + 8
    fun keyLength(at: Long): Int = segment.i32(at)
    fun userKeyLength(at: Long): Int = InternalKey.userKeyLength(segment.i32(at))
    fun isDeletion(at: Long): Boolean = segment.i32(at + 4) == -1

    fun valueOf(at: Long): MemorySegment? {
        val length = segment.i32(at + 4)
        if (length < 0) return null
        return segment.asSlice(at + 8 + keyLength(at), length.toLong())
    }

    fun userKeyBytes(at: Long): ByteArray = segment.readBytes(at + 8, userKeyLength(at))

    fun sequenceOf(at: Long): Long =
        InternalKey.sequenceOf(java.lang.Long.reverseBytes(segment.i64(at + 8 + userKeyLength(at))))

    fun advance(at: Long): Long = at + 8 + keyLength(at) + valueBytes(at)

    override fun close() {
        mapped.close()
    }

    private fun valueBytes(at: Long): Int = segment.i32(at + 4).coerceAtLeast(0)
}

class BloomBuilder(expectedEntries: Int) {
    private val bitCount = (expectedEntries * 10).coerceAtLeast(64)
    private val words = LongArray((bitCount + 63) / 64)

    fun add(key: ByteArray, length: Int) {
        val hash = Bloom.hash(key, length)
        var h = hash.toInt()
        val delta = (hash ushr 32).toInt() or 1
        repeat(6) {
            val bit = ((h.toLong() and 0xFFFFFFFFL) % bitCount).toInt()
            words[bit ushr 6] = words[bit ushr 6] or (1L shl (bit and 63))
            h += delta
        }
    }

    fun writeTo(out: OutputStream): Long {
        val buffer = ByteBuffer.allocate(8 + words.size * 8).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(bitCount)
        buffer.putInt(6)
        words.forEach(buffer::putLong)
        out.write(buffer.array())
        return buffer.capacity().toLong()
    }
}

class Bloom(private val segment: MemorySegment, private val offset: Long) {
    private val bitCount = segment.i32(offset)
    private val hashes = segment.i32(offset + 4)

    fun mightContain(userKey: ByteArray): Boolean {
        if (bitCount == 0) return true
        val hash = hash(userKey, userKey.size)
        var h = hash.toInt()
        val delta = (hash ushr 32).toInt() or 1
        repeat(hashes) {
            val bit = ((h.toLong() and 0xFFFFFFFFL) % bitCount).toInt()
            val word = segment.i64(offset + 8 + (bit ushr 6) * 8L)
            if (word and (1L shl (bit and 63)) == 0L) return false
            h += delta
        }
        return true
    }

    companion object {
        fun hash(key: ByteArray, length: Int): Long {
            var h = -0x340d631b7bdddcdbL
            for (i in 0 until length) {
                h = h xor (key[i].toLong() and 0xFF)
                h *= 0x100000001b3L
            }
            return h
        }
    }
}
