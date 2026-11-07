package com.tracel.storage.lsm.wal

/** How often the write-ahead log is forced to stable storage. */
sealed interface WalSyncPolicy {
    data object EveryBatch : WalSyncPolicy

    data class Interval(val millis: Long) : WalSyncPolicy

    data object Never : WalSyncPolicy
}
