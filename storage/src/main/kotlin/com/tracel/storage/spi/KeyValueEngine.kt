package com.tracel.storage.spi

import com.tracel.storage.ffm.Key
import java.lang.foreign.MemorySegment

/**
 * The one interface every storage port is written against.
 *
 * Every port in `com.tracel.storage.ports` is written against this and nothing else, so
 * swapping the engine underneath changes not a line of ledger code.
 *
 * Contract:
 * - Keys are compared unsigned-lexicographically. Nothing else is ever a valid ordering.
 * - Exactly one thread writes. Any number may read.
 * - [write] is the only durability boundary: everything in one batch becomes visible together
 *   or not at all, and `durable = true` means it survives losing the machine.
 */
interface KeyValueEngine : AutoCloseable {
    /**
     * Applies [batch] atomically.
     *
     * @param durable `true` forces the write-ahead log to stable storage before returning.
     * `false` publishes the batch to readers but leaves it in the page cache — see
     * [TracelStorage][com.tracel.storage.TracelStorage] for which one the pipeline uses where.
     */
    fun write(batch: MutationBatch, durable: Boolean)

    /** A consistent view. Everything committed before the call is in it; nothing after is. */
    fun snapshot(): EngineSnapshot

    /** Sends everything still buffered to stable storage. */
    fun sync()

    /** Flushes and compacts everything, then waits for it. */
    fun compactEverything()

    /** Numbers worth putting in a bug report. */
    fun stats(): EngineStats

    val name: String
}

/** A point-in-time view. Cheap to take, must be closed, and pins whatever it needs to stay alive. */
interface EngineSnapshot : AutoCloseable {
    /** The value at [key], or `null`. The segment is read-only and valid until this closes. */
    fun get(key: ByteArray): MemorySegment?

    /**
     * Every entry whose key starts with [prefix], in key order, starting at or after [from].
     *
     * There is deliberately no reverse cursor: every index that wants newest-first stores its
     * sequence inverted instead. See [Keys][com.tracel.storage.codec.Keys].
     */
    fun scan(prefix: ByteArray, from: ByteArray = prefix): EngineCursor
}

/** A forward cursor. Not thread-safe; one per reader, one at a time. */
interface EngineCursor : AutoCloseable {
    fun next(): Boolean

    /** The current key. A fresh array — cursors hand out keys that outlive their position. */
    fun key(): ByteArray

    /** The current value, read-only and valid only until the next [next]. */
    fun value(): MemorySegment

    /**
     * Repositions so the next [next] is the first key at or after [from], still under this
     * cursor's prefix. A spatial scan uses this to jump out of a y-bin once its newest remaining
     * row is already older than the query window, instead of walking years of a spawn chunk.
     */
    fun skipTo(from: ByteArray)
}

data class EngineStats(
    val liveBytes: Long,
    val segmentCount: Int,
    val memtableBytes: Long,
    val walBytes: Long,
    val writes: Long,
    val flushes: Long,
    val compactions: Long,
)

/** An ordered set of puts and deletes, applied as one. */
class MutationBatch {
    private val entries = HashMap<Key, ByteArray?>()
    private val ordered = java.util.TreeSet<Key>()
    private val undoKeys = ArrayList<Key>()
    private val undoValues = ArrayList<ByteArray?>()
    private var journaling = false

    val size: Int get() = entries.size

    fun put(key: ByteArray, value: ByteArray) {
        write(Key(key), value, journalled = true)
    }

    fun delete(key: ByteArray) {
        write(Key(key), null, journalled = true)
    }

    fun putPinned(key: ByteArray, value: ByteArray) {
        write(Key(key), value, journalled = false)
    }

    fun isEmpty(): Boolean = entries.isEmpty()

    fun clear() {
        entries.clear()
        ordered.clear()
        undoKeys.clear()
        undoValues.clear()
    }

    fun mark(): Int {
        journaling = true
        return undoKeys.size
    }

    fun rollbackTo(mark: Int) {
        for (i in undoKeys.size - 1 downTo mark) {
            val key = undoKeys[i]
            val previous = undoValues[i]
            if (previous === ABSENT) {
                entries.remove(key)
                ordered.remove(key)
            } else {
                entries[key] = previous
            }
        }
        truncate(mark)
    }

    fun release(mark: Int) {
        truncate(mark)
    }

    fun lookup(key: Key): Any? {
        val value = entries[key]
        if (value != null) return value
        return if (entries.containsKey(key)) null else MISSING
    }

    fun touches(key: Key): Boolean = entries.containsKey(key)

    fun valueOf(key: Key): ByteArray? = entries[key]

    fun keysFrom(from: Key): Iterator<Key> = ordered.tailSet(from, true).iterator()

    fun forEach(action: (ByteArray, ByteArray?) -> Unit) {
        ordered.forEach { key -> action(key.bytes, entries[key]) }
    }

    fun byteSize(): Long {
        var total = 0L
        entries.forEach { (key, value) -> total += key.size + (value?.size ?: 0) }
        return total
    }

    private fun write(key: Key, value: ByteArray?, journalled: Boolean) {
        val had = entries.containsKey(key)
        if (journalled && journaling) {
            undoKeys += key
            undoValues += if (had) entries[key] else ABSENT
        }
        entries[key] = value
        if (!had) ordered.add(key)
    }

    private fun truncate(mark: Int) {
        while (undoKeys.size > mark) {
            undoKeys.removeAt(undoKeys.size - 1)
            undoValues.removeAt(undoValues.size - 1)
        }
        if (mark == 0) journaling = false
    }

    companion object {
        val MISSING: Any = Any()
        private val ABSENT = ByteArray(0)
    }
}
