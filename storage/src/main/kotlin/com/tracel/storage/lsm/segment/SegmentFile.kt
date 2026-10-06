package com.tracel.storage.lsm.segment

import com.github.luben.zstd.Zstd
import com.tracel.platform.Versions
import com.tracel.storage.ffm.Bytes
import com.tracel.storage.ffm.Bytes.i32
import com.tracel.storage.ffm.Bytes.i64
import com.tracel.storage.ffm.Bytes.i8
import com.tracel.storage.ffm.Bytes.readBytes
import com.tracel.storage.ffm.MappedFile
import com.tracel.storage.ffm.SegmentCompare
import com.tracel.storage.lsm.InternalKey
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
 * data   -> repeated block:
 *           [u32 uncompressed][u32 flags|payload][payload]
 * index  -> [u32 keyLen][internal key][u64 blockOffset]  ... one per block
 * bloom  -> [u32 bitCount][u32 hashCount][bits]
 * footer -> 52 bytes, at EOF
 * ```
 *
 * Written once, `fsync`ed, then never touched again. Capture never reads this file.
 */
object SegmentFile {
    val MAGIC = "TSEG".toByteArray(Charsets.US_ASCII)

    const val VERSION = Versions.Format.SEGMENT
    const val FOOTER_BYTES = 40
    const val BLOCK_TARGET = 4096
    const val FLAG_ZSTD = 1 shl 31
    const val PAYLOAD_MASK = 0x7FFFFFFF
    const val FLUSH_ZSTD_LEVEL = 5
    const val COMPACTION_ZSTD_LEVEL = 19

    private val LE_INT: VarHandle = MethodHandles.byteArrayViewVarHandle(IntArray::class.java, ByteOrder.LITTLE_ENDIAN)
    private val LE_LONG: VarHandle =
        MethodHandles.byteArrayViewVarHandle(LongArray::class.java, ByteOrder.LITTLE_ENDIAN)

    class Writer(
        private val path: Path,
        expectedEntries: Int,
        private val zstdLevel: Int = FLUSH_ZSTD_LEVEL,
    ) : AutoCloseable {
        private val file = java.io.FileOutputStream(path.toFile())
        private val out: OutputStream = BufferedOutputStream(file, 1 shl 16)
        private val bloom = BloomBuilder(expectedEntries.coerceAtLeast(1))

        // The index is staged as the bytes it will become, not as a list of arrays to be
        // re-encoded at the end. Two ArrayLists meant one boxed Long and one retained key
        // reference per index point, and a second pass over both at finish().
        private var index = ByteArray(1 shl 14)
        private var indexUsed = 0

        private val header = ByteArray(16)
        private var block = ByteArray(BLOCK_TARGET + 512)
        private var blockUsed = 0
        private var blockFirstKey: ByteArray? = null

        private var dataBytes = 0L
        private var count = 0
        private var firstKey: ByteArray? = null
        private var lastKey: ByteArray = ByteArray(0)
        private var lastKeyLength = 0

        /** Adds a key / value pair to the segment. The key must be greater than the last one added. */
        fun add(internalKey: ByteArray, value: ByteArray?) {
            require(internalKey.size <= 0xFFFF) { "internal key ${internalKey.size} does not fit a u16 length" }
            if (blockUsed >= BLOCK_TARGET) flushBlock()
            val restart = blockUsed == 0
            val shared = if (restart) 0 else sharedPrefix(lastKey, lastKeyLength, internalKey)
            val unshared = internalKey.size - shared
            val valueLength = value?.size ?: -1
            var headerAt = 0
            headerAt = Varints.write(header, headerAt, shared)
            headerAt = Varints.write(header, headerAt, unshared)
            headerAt = Varints.write(header, headerAt, valueLength + 1)
            val payload = headerAt + unshared + (value?.size ?: 0)
            ensureBlock(payload)
            System.arraycopy(header, 0, block, blockUsed, headerAt)
            System.arraycopy(internalKey, shared, block, blockUsed + headerAt, unshared)
            if (value != null) System.arraycopy(value, 0, block, blockUsed + headerAt + unshared, value.size)
            blockUsed += payload
            if (restart) blockFirstKey = internalKey.copyOf()
            count++
            bloom.add(internalKey, internalKey.size - InternalKey.TRAILER_BYTES)
            if (firstKey == null) firstKey = internalKey
            if (lastKey.size < internalKey.size) lastKey = internalKey.copyOf()
            else System.arraycopy(internalKey, 0, lastKey, 0, internalKey.size)
            lastKeyLength = internalKey.size
        }

        /**
         * Finishes the segment and returns its metadata. The file is closed and durable on return.
         *
         * @return the segment's metadata, including its file size and first / last keys
         */
        fun finish(id: Long, level: Int, category: Int = 0, window: Long = SegmentMeta.NO_WINDOW): SegmentMeta {
            flushBlock()

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
            footer.put(MAGIC)
            out.write(footer.array())
            out.flush()
            file.fd.sync()
            file.close()

            return SegmentMeta(
                id,
                level,
                count,
                Files.size(path),
                firstKey ?: ByteArray(0),
                lastKey.copyOf(lastKeyLength),
                category,
                window,
            )
        }

        override fun close() {
            runCatching { out.close() }
            runCatching { file.close() }
        }

        private fun flushBlock() {
            if (blockUsed == 0) return
            val first = blockFirstKey ?: error("a non-empty block has no first key")
            stageIndex(first, dataBytes)
            writeBlock(block, blockUsed)
            blockUsed = 0
            blockFirstKey = null
        }

        private fun writeBlock(raw: ByteArray, length: Int) {
            val bound = Zstd.compressBound(length.toLong()).toInt()
            val packedBuf = ByteArray(bound)
            val packed = Zstd.compressByteArray(packedBuf, 0, bound, raw, 0, length, zstdLevel).toInt()
            val worthIt = packed in 1 until length
            val payload = if (worthIt) packed else length
            val flags = if (worthIt) payload or FLAG_ZSTD else payload
            val body = if (worthIt) packedBuf else raw

            LE_INT.set(header, 0, length)
            LE_INT.set(header, 4, flags)
            out.write(header, 0, 8)
            out.write(body, 0, payload)
            dataBytes += 8L + payload
        }

        private fun ensureBlock(more: Int) {
            val need = blockUsed + more
            if (need > block.size) block = block.copyOf(maxOf(need, block.size shl 1))
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

    /** Opens a segment for reading. The file is not modified, and the caller must close the reader. */
    fun open(path: Path, meta: SegmentMeta): SegmentReader = open(path, meta, BlockCache())

    internal fun open(path: Path, meta: SegmentMeta, cache: BlockCache): SegmentReader {
        val mapped = MappedFile.openRead(path)
        val segment = mapped.segment
        val footerAt = segment.byteSize() - FOOTER_BYTES
        require(footerAt >= 0) { "$path is too short to be a segment" }
        val magic = segment.readBytes(footerAt + FOOTER_BYTES - 4, 4)
        require(magic.contentEquals(MAGIC)) {
            "$path is not a Tracel segment (starts ${String(magic, Charsets.ISO_8859_1)})"
        }

        val version = segment.i32(footerAt + FOOTER_BYTES - 8)
        require(version == VERSION) { "$path is segment v$version, this build reads v$VERSION" }

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
            cache = cache,
        )
    }
}

/**
 * What the manifest knows about one segment.
 *
 * A [category] other than the engine's own `0` marks history, and then [window] says which stretch of time the
 * segment was written in; state has no window.
 */
data class SegmentMeta(
    val id: Long,
    val level: Int,
    val entries: Int,
    val fileBytes: Long,
    val firstKey: ByteArray,
    val lastKey: ByteArray,
    val category: Int = 0,
    val window: Long = NO_WINDOW,
) {
    val isHistory: Boolean get() = category != 0

    override fun equals(other: Any?): Boolean = other is SegmentMeta && other.id == id && other.level == level

    override fun hashCode(): Int = id.hashCode() * 31 + level

    companion object {
        const val NO_WINDOW = Long.MIN_VALUE
    }
}

class SegmentReader internal constructor(
    private val mapped: MappedFile,
    val meta: SegmentMeta,
    private val indexOffset: Long,
    private val indexEntries: IntArray,
    private val search: LongArray,
    private val dataEnd: Long,
    private val bloom: Bloom?,
    private val cache: BlockCache,
) : AutoCloseable {
    private val firstUserKey: ByteArray = meta.firstKey.copyOf(maxOf(0, meta.firstKey.size - InternalKey.TRAILER_BYTES))
    private val lastUserKey: ByteArray = meta.lastKey.copyOf(maxOf(0, meta.lastKey.size - InternalKey.TRAILER_BYTES))

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

    /** @return whether [userKey] falls between the first and the last key this segment holds. */
    fun inRange(userKey: ByteArray): Boolean =
        compareUnsigned(userKey, firstUserKey) >= 0 && compareUnsigned(userKey, lastUserKey) <= 0

    /**
     * @return whether anything this segment holds can be at or after [from] and under [prefix]. A window of history
     * that is nowhere near is not even opened for a scan.
     */
    fun mayHoldFrom(from: ByteArray, prefix: ByteArray): Boolean {
        if (meta.entries == 0) return false
        if (compareUnsigned(lastUserKey, from) < 0) return false
        val beyond = compareUnsigned(firstUserKey, prefix) > 0 && !startsWith(firstUserKey, prefix)
        return !beyond
    }

    /** @return true if the segment's bloom filter says the key might be present, or if there is no filter. */
    fun mightContain(userKey: ByteArray): Boolean = bloom?.mightContain(userKey) ?: true

    /** @return the offset of the first byte after the last block, which is also the start of the index. */
    fun end(): Long = dataEnd

    /** @return the uncompressed bytes of the block starting at [offset], or an empty array if there is none. */
    @Suppress("ConvertTwoComparisonsToRangeCheck")
    fun blockAt(offset: Long): ByteArray {
        if (offset < 0 || offset >= dataEnd) return EMPTY_BLOCK
        return cache.get(meta.id, offset) { decodeBlock(offset) }
    }

    /** The offset of the next block after the one starting at [offset], or [dataEnd] if there is none. */
    @Suppress("ConvertTwoComparisonsToRangeCheck")
    fun nextBlock(offset: Long): Long {
        if (offset < 0 || offset >= dataEnd) return dataEnd
        val packed = segment.i32(offset + 4)
        val payload = packed and SegmentFile.PAYLOAD_MASK
        return offset + 8 + payload
    }

    private fun decodeBlock(offset: Long): ByteArray {
        val uncompressed = segment.i32(offset)
        val packed = segment.i32(offset + 4)
        val compressed = packed and SegmentFile.FLAG_ZSTD != 0
        val payload = packed and SegmentFile.PAYLOAD_MASK
        val src = ByteArray(payload)
        MemorySegment.copy(segment, Bytes.I8, offset + 8, src, 0, payload)
        if (!compressed) return if (payload == uncompressed) src else src.copyOf(uncompressed)
        val dest = ByteArray(uncompressed)
        val got = Zstd.decompressByteArray(dest, 0, uncompressed, src, 0, payload)
        check(!Zstd.isError(got) && got == uncompressed.toLong()) {
            "segment ${path.fileName} block at $offset is corrupt: ${if (Zstd.isError(got)) Zstd.getErrorName(got) else "$got of $uncompressed bytes"}"
        }
        return dest
    }

    override fun close() {
        mapped.close()
    }

    private companion object {
        val EMPTY_BLOCK = ByteArray(0)
    }
}

/** Builds the blocked bloom filter [Bloom] reads. */
class BloomBuilder(expectedEntries: Int) {
    private val blocks =
        ((expectedEntries.toLong() * Bloom.BITS_PER_KEY + 511) / 512).coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
    private val words = LongArray(blocks shl 3)

    /** Adds a key to the filter. The key is hashed and the appropriate bits are set. */
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

    /** Writes the filter to [out] and returns the number of bytes written. */
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

        private val LE_LONG: VarHandle =
            MethodHandles.byteArrayViewVarHandle(LongArray::class.java, ByteOrder.LITTLE_ENDIAN)
        private val LE_INT: VarHandle =
            MethodHandles.byteArrayViewVarHandle(IntArray::class.java, ByteOrder.LITTLE_ENDIAN)

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
    /** Writes [value] to [dst] starting at [at], returning the index after the last byte written. */
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

    /**
     * Reads a varint from [data] starting at [offset].
     *
     * The result is a packed long. Read the code below.
     */
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

    /** @return the number of bytes read from the varint. */
    fun valueOf(packed: Long): Int = packed.toInt()

    /** @return the number of bytes read from the varint. */
    fun sizeOf(packed: Long): Int = (packed ushr 32).toInt()
}

private fun compareUnsigned(a: ByteArray, b: ByteArray): Int {
    val n = minOf(a.size, b.size)
    for (i in 0 until n) {
        val d = (a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)
        if (d != 0) return d
    }
    return a.size - b.size
}

private fun startsWith(key: ByteArray, prefix: ByteArray): Boolean {
    if (key.size < prefix.size) return false
    for (i in prefix.indices) if (key[i] != prefix[i]) return false
    return true
}
