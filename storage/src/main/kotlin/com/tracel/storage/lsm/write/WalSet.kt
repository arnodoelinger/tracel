package com.tracel.storage.lsm.write

import com.tracel.storage.lsm.state.Manifest
import com.tracel.storage.spi.MutationBatch
import java.nio.file.Path

/**
 * Keeps the current write-ahead log and a list of the older ones that are still in memory.
 *
 * The engine's [Manifest] keeps the same list on disk, so a restart can replay them to recover the last state.
 *
 * @see Wal
 * @see Manifest
 */
internal class WalSet(
    private val directory: Path,
    private val policy: SyncPolicy,
    firstId: Long,
) {
    private var current: Wal = Wal.create(Manifest.walPath(directory, firstId))
    private var live = arrayListOf(firstId)
    private var closedSyncs = 0L
    private var lastSyncMillis = System.currentTimeMillis()

    val ids: List<Long> get() = live.toList()

    val bytes: Long get() = current.bytes

    val syncs: Long get() = closedSyncs + current.syncs

    /** Appends a batch to the current log, with its sequence. */
    fun append(sequence: Long, batch: MutationBatch) = current.append(sequence, batch)

    /** Makes the last write durable, or does not, according to the configured policy. */
    fun sync() = current.sync()

    /**
     * Makes the last write durable, or does not, according to the configured policy.
     *
     * `Interval` is the only one with state: it is "at most one `fsync` per N milliseconds", so a
     * burst of writes pays for one and a quiet server still gets one soon after each.
     */
    fun syncPerPolicy() {
        when (policy) {
            SyncPolicy.EveryBatch -> current.sync()
            SyncPolicy.Never -> Unit
            is SyncPolicy.Interval -> {
                val now = System.currentTimeMillis()
                if (now - lastSyncMillis >= policy.millis) {
                    current.sync()
                    lastSyncMillis = now
                }
            }
        }
    }

    /** Closes the current log and opens [id]. The older ones stay: their tables are still in memory. */
    fun rollTo(id: Long) {
        closeCurrent()
        live += id
        current = Wal.create(Manifest.walPath(directory, id))
    }

    /** The same, and drops every older log — for a wipe, where those tables are being thrown away. */
    fun restartAt(id: Long) {
        closeCurrent()
        live = arrayListOf(id)
        current = Wal.create(Manifest.walPath(directory, id))
    }

    /** This log's memtable is on disk now, so a restart no longer needs it. */
    fun forget(id: Long) {
        live.remove(id)
    }

    /** Nothing is left in memory, so nothing needs replaying. Shutdown, after sealing. */
    fun clear() {
        live.clear()
    }

    /** Closes the current log, and makes sure its last write is durable. */
    fun close() {
        current.sync()
        current.close()
    }

    private fun closeCurrent() {
        current.sync()
        current.close()
        closedSyncs += current.syncs
    }
}
