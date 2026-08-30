package com.tracel.storage.lsm

import com.github.benmanes.caffeine.cache.Caffeine

/**
 * Uncompressed 4 KiB-class blocks, keyed by segment id and file offset.
 *
 * The capture path never touches this. Point gets and scans hit the same blocks over and over
 * (running totals, a chunk prefix), so a miss is one LZ4 of a few kilobytes on the storage
 * thread and then the array stays hot.
 */
internal class BlockCache(maxBytes: Long = 32L shl 20) {
    private val cache = Caffeine.newBuilder()
        .maximumWeight(maxBytes)
        .weigher { _: BlockId, value: ByteArray -> value.size }
        .build<BlockId, ByteArray>()

    fun get(segmentId: Long, offset: Long, decode: () -> ByteArray): ByteArray =
        cache.get(BlockId(segmentId, offset)) { decode() }

    private data class BlockId(val segmentId: Long, val offset: Long)
}
