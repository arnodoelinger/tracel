package com.tracel.storage.spi

/** One segment of history: the unit a purge throws away or rewrites. */
data class HistorySegment(
    val id: Long,
    val category: Int,
    val window: Long,
    val windowStartMillis: Long,
    val windowEndMillis: Long,
    val entries: Long,
    val bytes: Long,
)

/** What [KeyValueEngine.dropHistory] threw away. */
data class Dropped(val segments: Int, val rows: Long, val bytes: Long)

/** What [KeyValueEngine.rewriteSegment] took out of one segment. */
data class Rewritten(val rowsRemoved: Long, val bytesFreed: Long)
