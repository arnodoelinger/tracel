package com.tracel.storage.lsm.segment

import com.github.luben.zstd.Zstd
import com.tracel.platform.io.closeQuietly
import com.tracel.storage.lsm.InternalKey
import com.tracel.storage.lsm.disk.Disk
import com.tracel.storage.lsm.disk.LocalDisk
import java.io.BufferedOutputStream
import java.io.OutputStream
import java.lang.invoke.MethodHandles
import java.lang.invoke.VarHandle
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Path
import java.util.zip.CRC32C

private val LE_INT: VarHandle = MethodHandles.byteArrayViewVarHandle(IntArray::class.java, ByteOrder.LITTLE_ENDIAN)
private val LE_LONG: VarHandle = MethodHandles.byteArrayViewVarHandle(LongArray::class.java, ByteOrder.LITTLE_ENDIAN)

/** Writes one [SegmentFile], block by block, from internal keys handed over in ascending order. */
class SegmentBuilder(
    private val path: Path,
    expectedEntries: Int,
    private val zstdLevel: Int = SegmentFile.FLUSH_ZSTD_LEVEL,
    private val blockTarget: Int = SegmentFile.BLOCK_TARGET,
    private val version: Int = SegmentFile.VERSION,
    private val pointRead: (keyTag: Int) -> Boolean = { true },
    private val disk: Disk = LocalDisk,
) : AutoCloseable {
    private val tabled = version >= SegmentFile.RESTARTS_SINCE
    private val summed = version >= SegmentFile.CHECKSUMS_SINCE
    private val planed = version >= SegmentFile.PLANES_SINCE

    private val file = disk.write(path, fresh = true)
    private val out: OutputStream = BufferedOutputStream(file.stream(), 1 shl 16)
    private val bloom = BloomBuilder(expectedEntries.coerceAtLeast(1))
    private var lastTag = -1
    private var lastTagRead = true

    private var index = ByteArray(1 shl 14)
    private var indexUsed = 0

    private val header = ByteArray(16)
    private var block = ByteArray(blockTarget + 512)
    private var blockUsed = 0
    private var blockEntries = 0
    private var blockFirstKey: ByteArray? = null
    private var restarts = IntArray(64)
    private var restartCount = 0

    private var dataBytes = 0L
    private var count = 0
    private var firstKey: ByteArray? = null
    private var lastKey: ByteArray = ByteArray(0)
    private var lastKeyLength = 0

    /**
     * Adds a key / value pair to the segment.
     *
     * The key must be greater than the last one added.
     */
    fun add(internalKey: ByteArray, value: ByteArray?) {
        require(internalKey.size <= 0xFFFF) { "internal key ${internalKey.size} does not fit a u16 length." }
        if (blockUsed >= blockTarget) flushBlock()
        val first = blockUsed == 0
        val restart = if (tabled) blockEntries % SegmentFile.RESTART_INTERVAL == 0 else first
        if (restart && tabled) {
            if (restartCount == restarts.size) restarts = restarts.copyOf(restartCount shl 1)
            restarts[restartCount++] = blockUsed
        }
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
        blockEntries++
        if (first) blockFirstKey = internalKey.copyOf()
        count++
        val keyLength = internalKey.size - InternalKey.TRAILER_BYTES
        val tag = if (keyLength == 0) -1 else internalKey[0].toInt() and 0xFF
        if (tag != lastTag) {
            lastTag = tag
            lastTagRead = pointRead(tag)
        }
        if (lastTagRead) bloom.add(internalKey, keyLength)
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

        val footer = ByteBuffer.allocate(SegmentFile.FOOTER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        footer.putLong(indexOffset)
        footer.putInt(indexBytes.toInt())
        footer.putLong(bloomOffset)
        footer.putInt(bloomBytes.toInt())
        footer.putLong(count.toLong())
        footer.putInt(version)
        footer.put(SegmentFile.MAGIC)
        out.write(footer.array())
        out.flush()
        file.sync()
        file.close()

        return SegmentMeta(
            id,
            level,
            count,
            disk.size(path),
            firstKey ?: ByteArray(0),
            lastKey.copyOf(lastKeyLength),
            category,
            window,
        )
    }

    override fun close() {
        out.closeQuietly()
        file.closeQuietly()
    }

    private fun flushBlock() {
        if (blockUsed == 0) return
        val first = blockFirstKey ?: error("a non-empty block has no first key.")
        stageIndex(first, dataBytes)
        if (tabled) {
            ensureBlock(restartCount * 4 + 4)
            for (i in 0 until restartCount) LE_INT.set(block, blockUsed + i * 4, restarts[i])
            LE_INT.set(block, blockUsed + restartCount * 4, restartCount)
            blockUsed += restartCount * 4 + 4
        }
        writeBlock(block, blockUsed)
        blockUsed = 0
        blockEntries = 0
        restartCount = 0
        blockFirstKey = null
    }

    private fun writeBlock(raw: ByteArray, length: Int) {
        var inputLength = length
        var body = zstd(raw, length)
        var flags = SegmentFile.FLAG_ZSTD
        if (planed) {
            val turned = BlockPlanes.encode(raw, length)
            val packed = zstd(turned, turned.size)
            if (packed != null && (body == null || packed.size < body.size)) {
                inputLength = turned.size
                body = packed
                flags = SegmentFile.FLAG_ZSTD or SegmentFile.FLAG_PLANES
            }
        }
        if (body == null || body.size >= length) {
            inputLength = length
            body = raw.copyOf(length)
            flags = 0
        }
        val payload = body.size
        flags = flags or payload

        LE_INT.set(header, 0, inputLength)
        LE_INT.set(header, 4, flags)
        out.write(header, 0, 8)
        out.write(body, 0, payload)
        dataBytes += 8L + payload
        if (summed) {
            val crc = CRC32C().apply {
                update(header, 0, 8)
                update(body, 0, payload)
            }.value.toInt()
            LE_INT.set(header, 0, crc)
            out.write(header, 0, SegmentFile.CHECKSUM_BYTES)
            dataBytes += SegmentFile.CHECKSUM_BYTES
        }
    }

    private fun zstd(input: ByteArray, length: Int): ByteArray? {
        val bound = Zstd.compressBound(length.toLong()).toInt()
        val packed = ByteArray(bound)
        val size = Zstd.compressByteArray(packed, 0, bound, input, 0, length, zstdLevel)
        return if (Zstd.isError(size) || size <= 0) null else packed.copyOf(size.toInt())
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
