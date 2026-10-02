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
    val name: String

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

    /** Writes what is still in memory out as segments, so that all of it is history a purge can see. */
    fun flush()

    /** Flushes and compacts everything, then waits for it. */
    fun compactEverything()

    /** Throws the whole store away and leaves an empty one behind. */
    fun wipe()

    /** The segments of history of one [category], newest window last. */
    fun history(category: Int): List<HistorySegment>

    /**
     * Throws away every segment of [category] whose window ended at or before [endedBefore], as files: nothing is read,
     * nothing is rewritten, and the space is back when this returns.
     */
    fun dropHistory(category: Int, endedBefore: Long): Dropped

    /** Reads what is under [prefix] in segment [id] and nothing else; `null` if the segment is gone. */
    fun <T> readSegment(id: Long, prefix: ByteArray, read: (EngineCursor) -> T): T?

    /**
     * Replaces segment [id] with one that has only the rows [keep] says yes to, which is how a purge takes some of a
     * window and leaves the rest. [keep] is called twice per row and has to answer the same both times.
     */
    fun rewriteSegment(id: Long, keep: (key: ByteArray, value: MemorySegment?) -> Boolean): Rewritten

    /** Numbers worth putting in a bug report. */
    fun stats(): EngineStats
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

    fun key(): ByteArray

    fun value(): MemorySegment

    fun keyLength(): Int = key().size

    fun keyByte(at: Int): Byte = key()[at]

    fun keyU32(at: Int): Int {
        val key = key()
        return ((key[at].toInt() and 0xFF) shl 24) or ((key[at + 1].toInt() and 0xFF) shl 16) or
                ((key[at + 2].toInt() and 0xFF) shl 8) or (key[at + 3].toInt() and 0xFF)
    }

    fun keyU64(at: Int): Long {
        val key = key()
        var value = 0L
        for (i in 0 until 8) value = (value shl 8) or (key[at + i].toLong() and 0xFF)
        return value
    }

    fun skipTo(from: ByteArray)
}

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
