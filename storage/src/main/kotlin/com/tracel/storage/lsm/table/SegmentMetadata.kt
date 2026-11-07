package com.tracel.storage.lsm.table

/**
 * What the manifest knows about one segment.
 *
 * A [category] other than the engine's own `0` marks history, and then [window] says which stretch of time the
 * segment was written in; state has no window.
 */
data class SegmentMetadata(
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

    override fun equals(other: Any?): Boolean = other is SegmentMetadata && other.id == id && other.level == level

    override fun hashCode(): Int = id.hashCode() * 31 + level

    companion object {
        const val NO_WINDOW = Long.MIN_VALUE
    }
}
