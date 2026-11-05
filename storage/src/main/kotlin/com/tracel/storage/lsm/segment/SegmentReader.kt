package com.tracel.storage.lsm.segment

import com.github.luben.zstd.Zstd
import com.tracel.storage.ffm.Bytes
import com.tracel.storage.ffm.Bytes.i32
import com.tracel.storage.ffm.Bytes.readBytes
import com.tracel.storage.ffm.MappedFile
import com.tracel.storage.ffm.SegmentCompare
import com.tracel.storage.lsm.InternalKey
import java.lang.Long.compareUnsigned as compareUnsignedLongs
import java.lang.foreign.MemorySegment
import java.nio.file.Path
import java.util.zip.CRC32C

/**
 * One open [SegmentFile]: the index searched in place, the bloom filter,
 * and blocks decoded through the [BlockCache].
 *
 * Owns the mapping. Every [MemorySegment] it hands out dies with [close],
 * which is why the engine retires a reader instead of closing it while a
 * snapshot may still read it.
 */
class SegmentReader internal constructor(
    private val mapped: MappedFile,
    val meta: SegmentMeta,
    private val indexOffset: Long,
    private val indexEntries: IntArray,
    private val search: LongArray,
    private val dataEnd: Long,
    private val bloom: Bloom?,
    private val cache: BlockCache,
    val restarts: Boolean,
    private val checksums: Boolean,
) : AutoCloseable {
    private val firstUserKey: ByteArray = meta.firstKey.copyOf(maxOf(0, meta.firstKey.size - InternalKey.TRAILER_BYTES))
    private val lastUserKey: ByteArray = meta.lastKey.copyOf(maxOf(0, meta.lastKey.size - InternalKey.TRAILER_BYTES))

    val segment: MemorySegment get() = mapped.segment
    val path: Path get() = mapped.path

    /**
     * Data offset of the restart whose index key is the greatest one that is still `<=` the
     * target, or `0` when the target is before every index key.
     *
     * The binary search runs entirely over [search], an eight-byte big-endian prefix of every
     * index key, sitting in one flat contiguous array, and never ever dereferences an index record
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
                compareUnsignedLongs(prefix, target)
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
     * @return whether anything this segment holds can be at or after [from] and
     * under [prefix]. A window of history that is nowhere near is not even opened
     * for a scan.
     */
    fun mayHoldFrom(from: ByteArray, prefix: ByteArray): Boolean {
        if (meta.entries == 0) return false
        if (compareUnsigned(lastUserKey, from) < 0) return false
        val beyond = compareUnsigned(firstUserKey, prefix) > 0 && !startsWith(firstUserKey, prefix)
        return !beyond
    }

    /** @return whether this segment holds anything before [to]. */
    fun startsBefore(to: ByteArray): Boolean = meta.entries > 0 && compareUnsigned(firstUserKey, to) < 0

    /**
     * The user key each block starts with, for the blocks starting at
     * or after [from], before [to] and under [prefix].
     */
    fun blockKeys(prefix: ByteArray, from: ByteArray, to: ByteArray?, into: MutableList<ByteArray>) {
        for (i in indexEntries.indices) {
            val entry = indexOffset + indexEntries[i]
            val length = segment.i32(entry) - InternalKey.TRAILER_BYTES
            if (SegmentCompare.compare(segment, entry + 4, length, from) < 0) continue
            if (!SegmentCompare.startsWith(segment, entry + 4, length, prefix)) {
                if (SegmentCompare.compare(segment, entry + 4, length, prefix) > 0) return
                continue
            }
            if (to != null && SegmentCompare.compare(segment, entry + 4, length, to) >= 0) return
            into += segment.readBytes(entry + 4, length)
        }
    }

    /**
     * @return true if the segment's bloom filter says the key might be present,
     * or if there is no filter.
     */
    fun mightContain(userKey: ByteArray): Boolean = bloom?.mightContain(userKey) ?: true

    /**
     * @return the offset of the first byte after the last block,
     * which is also the start of the index.
     */
    fun end(): Long = dataEnd

    /**
     * @return the uncompressed bytes of the block starting at [offset],
     * or an empty array if there is none.
     */
    @Suppress("ConvertTwoComparisonsToRangeCheck")
    fun blockAt(offset: Long): ByteArray {
        if (offset < 0 || offset >= dataEnd) return EMPTY_BLOCK
        return cache.get(meta.id, offset) { decodeBlock(offset) }
    }

    /**
     * The offset of the next block after the one starting at [offset],
     * or [dataEnd] if there is none.
     */
    @Suppress("ConvertTwoComparisonsToRangeCheck")
    fun nextBlock(offset: Long): Long {
        if (offset < 0 || offset >= dataEnd) return dataEnd
        val packed = segment.i32(offset + 4)
        val payload = packed and SegmentFile.PAYLOAD_MASK
        return offset + 8 + payload + trailer()
    }

    override fun close() {
        mapped.close()
    }

    private fun trailer(): Int = if (checksums) SegmentFile.CHECKSUM_BYTES else 0

    private fun decodeBlock(offset: Long): ByteArray {
        val uncompressed = segment.i32(offset)
        val packed = segment.i32(offset + 4)
        val compressed = packed and SegmentFile.FLAG_ZSTD != 0
        val payload = packed and SegmentFile.PAYLOAD_MASK
        check(offset + 8L + payload + trailer() <= dataEnd) {
            "segment ${path.fileName} block at $offset is corrupt: its length runs past the data."
        }
        val scratch = Scratch.current()
        val length = 8 + payload
        val buffer = scratch.buffer(length)
        MemorySegment.copy(segment, Bytes.I8, offset, buffer, 0, length)
        if (checksums) {
            scratch.crc.reset()
            scratch.crc.update(buffer, 0, length)
            check(scratch.crc.value.toInt() == segment.i32(offset + 8 + payload)) {
                "segment ${path.fileName} block at $offset is corrupt: checksum mismatch."
            }
        }
        val block = ByteArray(uncompressed)
        if (!compressed) {
            System.arraycopy(buffer, 8, block, 0, minOf(payload, uncompressed))
            return block
        }
        val got = Zstd.decompressByteArray(block, 0, uncompressed, buffer, 8, payload)
        check(!Zstd.isError(got) && got == uncompressed.toLong()) {
            "segment ${path.fileName} block at $offset is corrupt: ${if (Zstd.isError(got)) Zstd.getErrorName(got) else "$got of $uncompressed bytes"}."
        }
        if (packed and SegmentFile.FLAG_PLANES != 0) return BlockPlanes.decode(block)
        return block
    }

    private class Scratch {
        val crc = CRC32C()
        private var bytes = ByteArray(INITIAL)

        fun buffer(length: Int): ByteArray {
            if (bytes.size < length) bytes = ByteArray(maxOf(length, bytes.size * 2))
            return bytes
        }

        companion object {
            private const val INITIAL = 1 shl 16
            private val local = ThreadLocal.withInitial { Scratch() }

            fun current(): Scratch = local.get()
        }
    }

    private companion object {
        val EMPTY_BLOCK = ByteArray(0)
    }
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
