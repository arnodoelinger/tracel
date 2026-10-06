package com.tracel.engine.store

/** How often the write-ahead log is forced to stable storage. */
public sealed interface StoreSync {
    public data object EveryBatch : StoreSync
    public data class Interval(public val millis: Long) : StoreSync
    public data object Never : StoreSync
}

/**
 * How the store is sized and when it syncs.
 *
 * @property memtableBytes how much one in-memory table holds before it is written out
 * @property maxFrozenMemtables how many full tables may wait to be written out
 * @property ringSlots how many events the capture queue holds
 */
public data class StoreSettings(
    public val memtableBytes: Long = 16L * 1024 * 1024,
    public val maxFrozenMemtables: Int = 3,
    public val sync: StoreSync = StoreSync.EveryBatch,
    public val ringSlots: Int = DEFAULT_RING_SLOTS,
) {
    public companion object {
        public const val DEFAULT_RING_SLOTS: Int = 1 shl 16
        public const val MIN_MEMTABLE_BYTES: Long = 1L shl 20
        public const val MIN_PENDING_FLUSHES: Int = 1
    }
}
