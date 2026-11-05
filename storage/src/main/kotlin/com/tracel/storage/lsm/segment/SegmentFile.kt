package com.tracel.storage.lsm.segment

import com.tracel.platform.Versions
import com.tracel.storage.ffm.Bytes.i32
import com.tracel.storage.ffm.Bytes.i64
import com.tracel.storage.ffm.Bytes.readBytes
import com.tracel.storage.ffm.MappedFile
import com.tracel.storage.ffm.SegmentCompare
import com.tracel.storage.lsm.disk.Disk
import com.tracel.storage.lsm.disk.LocalDisk
import java.nio.file.Path

/**
 * An immutable sorted run.
 *
 * ```
 * data   -> repeated block:
 *           [u32 uncompressed][u32 flags|payload][payload][u32 crc32c]
 *           payload, uncompressed: the records, then [u32 offset] per restart, then [u32 restarts]
 *           or, with FLAG_PLANES, the same block as [BlockPlanes]; v2 on
 *           crc32c covers the eight header bytes and the payload as stored; absent before v2
 * index  -> [u32 keyLen][internal key][u64 blockOffset]  ... one per block
 * bloom  -> [u32 bitCount][u32 hashCount][bits]
 * footer -> 40 bytes, at EOF
 * ```
 *
 * Written once by [SegmentBuilder], `fsync`ed, then never touched again; [open] maps it for a [SegmentReader].
 * Capture never reads this file.
 *
 * This object is the format: the constants every writer and reader of a segment agree on.
 */
object SegmentFile {
    val MAGIC = "TSEG".toByteArray(Charsets.US_ASCII)

    const val VERSION = Versions.Format.SEGMENT
    const val OLDEST_VERSION = 1
    const val RESTARTS_SINCE = 2
    const val CHECKSUMS_SINCE = 2
    const val PLANES_SINCE = 2
    const val CHECKSUM_BYTES = 4
    const val RESTART_INTERVAL = 32
    const val FOOTER_BYTES = 40
    const val BLOCK_TARGET = 32 * 1024
    const val FLAG_ZSTD = 1 shl 31
    const val FLAG_PLANES = 1 shl 30
    const val PAYLOAD_MASK = 0x3FFFFFFF
    const val FLUSH_ZSTD_LEVEL = 5
    const val COMPACTION_ZSTD_LEVEL = 19

    /** Opens a segment for reading. The file is not modified, and the caller must close the reader. */
    fun open(path: Path, meta: SegmentMeta, disk: Disk = LocalDisk): SegmentReader = open(path, meta, BlockCache(), disk)

    internal fun open(path: Path, meta: SegmentMeta, cache: BlockCache, disk: Disk): SegmentReader {
        val mapped = MappedFile.openRead(path, disk)
        val segment = mapped.segment
        val footerAt = segment.byteSize() - FOOTER_BYTES
        require(footerAt >= 0) { "$path is too short to be a segment" }
        val magic = segment.readBytes(footerAt + FOOTER_BYTES - 4, 4)
        require(magic.contentEquals(MAGIC)) {
            "$path is not a Tracel segment (starts ${String(magic, Charsets.ISO_8859_1)})"
        }

        val version = segment.i32(footerAt + FOOTER_BYTES - 8)
        require(version in OLDEST_VERSION..VERSION) {
            "$path is segment v$version, this build reads v$OLDEST_VERSION to v$VERSION"
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
            cache = cache,
            restarts = version >= RESTARTS_SINCE,
            checksums = version >= CHECKSUMS_SINCE,
        )
    }
}
