package com.tracel.storage.spi

/** Engine statistics. */
data class EngineStats(
    val syncs: Long,
    val liveBytes: Long,
    val segmentCount: Int,
    val memtableBytes: Long,
    val walBytes: Long,
    val writes: Long,
    val flushes: Long,
    val compactions: Long,
)
