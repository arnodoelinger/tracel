package com.tracel.storage.lsm

import com.tracel.storage.ffm.Bytes.i32
import com.tracel.storage.ffm.Bytes.i64
import com.tracel.storage.ffm.Bytes.i8
import com.tracel.storage.ffm.MappedFile
import com.tracel.storage.ffm.SegmentCompare
import java.io.BufferedOutputStream
import java.io.OutputStream
import java.lang.foreign.MemorySegment
import java.lang.invoke.MethodHandles
import java.lang.invoke.VarHandle
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path

/**
 * An immutable sorted run.
 *
 * ```
 * data ->
 * [varint shared][varint unshared][varint valueLen+1][unshared key][value]
 * // valueLen+1 is 0 for a tombstone, 1 for an empty value, n+1 for n payload bytes
 *
 * index  -> [u32 keyLen][internal key][u64 dataOffset] ... restart every INDEX_STRIDE of data
 * bloom  -> [u32 bitCount][u32 hashCount][bits]        ... over user keys
 * footer -> 40 bytes, at EOF
 * ```
 *
 * Written once, `fsync`ed, then never touched again.
 */
object SegmentFile {
    const val MAGIC = 0x54524C53
    const val VERSION = 4
    const val OLDEST_READABLE_VERSION = 4
    const val FOOTER_BYTES = 40

    // Don't change this
    private const val INDEX_STRIDE = 1024

    private val LE_INT: VarHandle = MethodHandles.byteArrayViewVarHandle(IntArray::class.java, ByteOrder.LITTLE_ENDIAN)
    private val LE_LONG: VarHandle = MethodHandles.byteArrayViewVarHandle(LongArray::class.java, ByteOrder.LITTLE_ENDIAN)

    class Writer(private val path: Path, expectedEntries: Int) : AutoCloseable {
        private val file = java.io.FileOutputStream(path.toFile())
        private val out: OutputStream = BufferedOutputStream(file, 1 shl 16)
        private val bloom = BloomBuilder(expectedEntries.coerceAtLeast(1))

        // The index is staged as the bytes it will become, not as a list of arrays to be
        // re-encoded at the end. Two ArrayLists meant one boxed Long and one retained key
        // reference per index point, and a second pass over both at finish().
        private var index = ByteArray(1 shl 14)
        private var indexUsed = 0

        private val header = ByteArray(16)

        private var dataBytes = 0L
        private var sinceIndex = 0L
        private var count = 0
        private var firstKey: ByteArray? = null
        private var lastKey: ByteArray = ByteArray(0)
        private var lastKeyLength = 0

        fun add(internalKey: ByteArray, value: ByteArray?) {
            require(internalKey.size <= 0xFFFF) { "internal key ${internalKey.size} does not fit a u16 length" }
            val restart = sinceIndex == 0L || sinceIndex >= INDEX_STRIDE
            if (restart) {
                stageIndex(internalKey, dataBytes)
                sinceIndex = 0
            }
            val shared = if (restart) 0 else sharedPrefix(lastKey, lastKeyLength, internalKey)
            val unshared = internalKey.size - shared
            val valueLength = value?.size ?: -1
            var headerAt = 0
            headerAt = Varints.write(header, headerAt, shared)
            headerAt = Varints.write(header, headerAt, unshared)
            headerAt = Varints.write(header, headerAt, valueLength + 1)
            out.write(header, 0, headerAt)
            out.write(internalKey, shared, unshared)
            if (value != null) out.write(value)

            val entryBytes = headerAt.toLong() + unshared + (value?.size ?: 0)
            dataBytes += entryBytes
            sinceIndex += entryBytes
            count++
            bloom.add(internalKey, internalKey.size - InternalKey.TRAILER_BYTES)
            if (firstKey == null) firstKey = internalKey
            if (lastKey.size < internalKey.size) lastKey = internalKey.copyOf()
            else System.arraycopy(internalKey, 0, lastKey, 0, internalKey.size)
            lastKeyLength = internalKey.size
        }

        fun finish(id: Long, level: Int): SegmentMeta {
            val indexOffset = dataBytes
            val indexBytes = indexUsed.toLong()
            out.write(index, 0, indexUsed)

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

            return SegmentMeta(id, level, count, Files.size(path), firstKey ?: ByteArray(0), lastKey.copyOf(lastKeyLength))
        }

        override fun close() {
            runCatching { out.close() }
            runCatching { file.close() }
        }

        private fun sharedPrefix(previous: ByteArray, previousLength: Int, next: ByteArray): Int {
            val n = minOf(previousLength, next.size)
            var i = 0
            while (i < n && previous[i] == next[i]) i++
            return i
        }

        private fun stageIndex(key: ByteArray, offset: Long) {
            val at = indexUsed
            val end = at + 12 + key.size
            var buffer = index
            if (end > buffer.size) {
                buffer = buffer.copyOf(maxOf(end, buffer.size shl 1))
                index = buffer
            }
            LE_INT.set(buffer, at, key.size)
            System.arraycopy(key, 0, buffer, at + 4, key.size)
            LE_LONG.set(buffer, at + 4 + key.size, offset)
            indexUsed = end
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
        require(version in OLDEST_READABLE_VERSION..VERSION) {
            "$path was written by segment format v$version, this build reads v$OLDEST_READABLE_VERSION-v$VERSION"
        }

        val indexOffset = segment.i64(footerAt)
        val indexBytes = segment.i32(footerAt + 8)
        val bloomOffset = segment.i64(footerAt + 12)
        val bloomBytes = segment.i32(footerAt + 20)
        val indexEnd = indexOffset + indexBytes
        var count = 0
        var at = indexOffset
        while (at < indexEnd) {
            count++
            at += 4L + segment.i32(at) + 8L
        }

        val entries = IntArray(count)
        val search = LongArray(count shl 1)
        at = indexOffset
        var i = 0
        while (at < indexEnd) {
            val keyLength = segment.i32(at)
            entries[i] = (at - indexOffset).toInt()
            search[i shl 1] = SegmentCompare.prefixOf(segment, at + 4)
            search[(i shl 1) + 1] = segment.i64(at + 4 + keyLength)
            i++
            at += 4L + keyLength + 8L
        }

        return SegmentReader(
            mapped,
            meta,
            indexOffset,
            entries,
            search,
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

class SegmentReader internal constructor(
    private val mapped: MappedFile,
    val meta: SegmentMeta,
    private val indexOffset: Long,
    private val indexEntries: IntArray,
    private val search: LongArray,
    private val dataEnd: Long,
    private val bloom: Bloom?,
) : AutoCloseable {
    val segment: MemorySegment get() = mapped.segment
    val path: Path get() = mapped.path

    /**
     * Data offset of the restart whose index key is the greatest one that is still `<=` the
     * target, or `0` when the target is before every index key.
     *
     * The binary search runs entirely over [search] — an eight-byte big-endian prefix of every
     * index key, sitting in one flat contiguous array — and never dereferences an index record
     * at all unless two prefixes tie. Walking the prefix-encoded run from that restart is the
     * cursor's job: an offset in the data stream is not enough to reconstruct a key.
     */
    fun restartAt(internalKey: ByteArray, length: Int): Long {
        val target = SegmentCompare.prefixOf(internalKey, length)
        val table = search
        var low = 0
        var high = indexEntries.size - 1
        var block = 0L
        while (low <= high) {
            val mid = (low + high) ushr 1
            val slot = mid shl 1
            val prefix = table[slot]
            val cmp = if (prefix != target) {
                java.lang.Long.compareUnsigned(prefix, target)
            } else {
                val entry = indexOffset + indexEntries[mid]
                SegmentCompare.compare(segment, entry + 4, segment.i32(entry), internalKey, length)
            }
            if (cmp <= 0) {
                block = table[slot + 1]
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return block
    }

    fun mightContain(userKey: ByteArray): Boolean = bloom?.mightContain(userKey) ?: true

    fun end(): Long = dataEnd

    override fun close() {
        mapped.close()
    }
}

/** Builds the blocked bloom filter [Bloom] reads. */
class BloomBuilder(expectedEntries: Int) {
    private val blocks =
        ((expectedEntries.toLong() * Bloom.BITS_PER_KEY + 511) / 512).coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
    private val words = LongArray(blocks shl 3)

    fun add(key: ByteArray, length: Int) {
        val hash = Bloom.hash(key, length)
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

    fun writeTo(out: OutputStream): Long {
        val buffer = ByteBuffer.allocate(8 + words.size * 8).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(blocks shl 9)
        buffer.putInt(Bloom.LANES)
        for (word in words) buffer.putLong(word)
        out.write(buffer.array())
        return buffer.capacity().toLong()
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

/** Unsigned LEB128. Record headers are three of these: `shared`, `unshared`, `valueLen+1`. */
internal object Varints {
    fun write(dst: ByteArray, at: Int, value: Int): Int {
        require(value >= 0) { "varint is unsigned, got $value" }
        var v = value
        var i = at
        while (v >= 0x80) {
            dst[i++] = ((v and 0x7F) or 0x80).toByte()
            v = v ushr 7
        }
        dst[i++] = v.toByte()
        return i
    }

    fun read(data: MemorySegment, offset: Long): Long {
        var result = 0
        var shift = 0
        var at = offset
        while (true) {
            val b = data.i8(at).toInt() and 0xFF
            at++
            result = result or ((b and 0x7F) shl shift)
            if (b and 0x80 == 0) break
            shift += 7
            check(shift <= 28) { "varint too long at $offset" }
        }
        return ((at - offset) shl 32) or (result.toLong() and 0xFFFFFFFFL)
    }

    fun valueOf(packed: Long): Int = packed.toInt()

    fun sizeOf(packed: Long): Int = (packed ushr 32).toInt()
}
