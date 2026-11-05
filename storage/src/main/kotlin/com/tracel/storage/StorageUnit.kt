package com.tracel.storage

import com.tracel.platform.io.reason
import com.tracel.storage.ffm.Key
import com.tracel.storage.spi.EngineCursor
import com.tracel.storage.spi.EngineSnapshot
import com.tracel.storage.spi.KeyValueEngine
import com.tracel.storage.spi.MutationBatch
import java.lang.foreign.MemorySegment
import java.util.logging.Level
import java.util.logging.Logger

/**
 * One unit of storage work: a snapshot to read from and a batch to write into, committed
 * together or not at all.
 *
 * Not thread-safe: the thread that opened it writes it.
 */
class StorageUnit(
    private val snapshot: EngineSnapshot,
    val batch: MutationBatch,
) : AutoCloseable {
    private var onCommit: ArrayList<() -> Unit>? = null
    private var onAbort: ArrayList<() -> Unit>? = null
    private val hookLogger = Logger.getLogger(StorageUnit::class.java.name)

    fun get(key: ByteArray): MemorySegment? {
        val overlay = batch.lookup(Key(key))
        if (overlay !== MutationBatch.MISSING) return (overlay as ByteArray?)?.let(MemorySegment::ofArray)
        return snapshot.get(key)
    }

    fun exists(key: ByteArray): Boolean = get(key) != null

    fun put(key: ByteArray, value: ByteArray) {
        batch.put(key, value)
    }

    fun delete(key: ByteArray) {
        batch.delete(key)
    }

    fun putPinned(key: ByteArray, value: ByteArray) {
        batch.putPinned(key, value)
    }

    fun mark(): Int = batch.mark()

    fun rollbackTo(mark: Int) {
        batch.rollbackTo(mark)
    }

    fun release(mark: Int) {
        batch.release(mark)
    }

    /** Runs [action] once the batch has landed. A unit that throws never does. */
    fun afterCommit(action: () -> Unit) {
        (onCommit ?: ArrayList<() -> Unit>().also { onCommit = it }) += action
    }

    /** What [afterCommit] queued, for whoever just wrote the batch. A hook that throws is logged and skipped. */
    internal fun committed() {
        onCommit?.forEach { hook ->
            runCatching(hook).onFailure {
                hookLogger.log(
                    Level.WARNING,
                    "Commit hook failed (${it.reason()}).",
                    it
                )
            }
        }
    }

    /** Runs [action] if the batch never lands: whatever was cached off it is a lie. */
    fun afterAbort(action: () -> Unit) {
        (onAbort ?: ArrayList<() -> Unit>().also { onAbort = it }) += action
    }

    /**
     * What [afterAbort] queued, for whoever just dropped the batch.
     *
     * A hook that throws is logged and skipped.
     */
    internal fun aborted() {
        onAbort?.forEach { hook ->
            runCatching(hook).onFailure {
                hookLogger.log(
                    Level.WARNING,
                    "Abort hook failed (${it.reason()}).",
                    it
                )
            }
        }
    }

    fun scan(prefix: ByteArray, from: ByteArray = prefix, to: ByteArray? = null): EngineCursor {
        if (batch.isEmpty()) return snapshot.scan(prefix, from, to)
        return OverlayCursor(batch, snapshot.scan(prefix, from, to), prefix, from, to)
    }

    fun splitKeys(prefix: ByteArray, from: ByteArray, to: ByteArray?, parts: Int): List<ByteArray> =
        snapshot.splitKeys(prefix, from, to, parts)

    override fun close() {
        snapshot.close()
    }

    companion object {
        fun open(engine: KeyValueEngine): StorageUnit = StorageUnit(engine.snapshot(), MutationBatch())
    }
}
