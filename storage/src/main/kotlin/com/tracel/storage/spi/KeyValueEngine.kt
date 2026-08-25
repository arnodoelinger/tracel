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
    private val entries = java.util.TreeMap<Key, ByteArray?>()
    private val undoKeys = ArrayList<Key>()
    private val undoValues = ArrayList<ByteArray?>()
    private var journaling = false

    val size: Int get() = entries.size

    fun put(key: ByteArray, value: ByteArray) {
        val wrapped = Key(key)
        journal(wrapped)
        entries[wrapped] = value
    }

    fun delete(key: ByteArray) {
        val wrapped = Key(key)
        journal(wrapped)
        entries[wrapped] = null
    }

    /**
     * A write the undo journal deliberately does not cover.
     *
     * Interning is the only caller. An id handed out to a rolled-back event is still cached in
     * memory, so unwinding its mapping would leave a live ID with nothing on disk explaining
     * what it means — and interning is monotone, so persisting one nobody ended up using costs
     * a few bytes and no correctness at all.
     */
    fun putPinned(key: ByteArray, value: ByteArray) {
        entries[Key(key)] = value
    }

    fun isEmpty(): Boolean = entries.isEmpty()

    fun clear() {
        entries.clear()
        undoKeys.clear()
        undoValues.clear()
    }

    /** Opens a savepoint. Everything written after it can be undone by [rollbackTo]. */
    fun mark(): Int {
        journaling = true
        return undoKeys.size
    }

    /** Unwinds every journalled write made since [mark], newest first. */
    fun rollbackTo(mark: Int) {
        for (i in undoKeys.size - 1 downTo mark) {
            val key = undoKeys[i]
            val previous = undoValues[i]
            if (previous === ABSENT) entries.remove(key) else entries[key] = previous
        }
        truncate(mark)
    }

    /** Keeps everything written since [mark] and forgets how to undo it. */
    fun release(mark: Int) {
        truncate(mark)
    }

    /** True when this batch has an opinion about [key] — a value or a deletion. */
    fun touches(key: Key): Boolean = entries.containsKey(key)

    /** This batch's value for [key]; `null` is a deletion, so check [touches] first. */
    fun valueOf(key: Key): ByteArray? = entries[key]

    /** Entries at or after [from], in key order — the overlay half of a read-your-writes scan. */
    fun from(from: Key): Iterator<Map.Entry<Key, ByteArray?>> = entries.tailMap(from, true).entries.iterator()

    /** Iterates in key order. A `null` value means a deletion. */
    fun forEach(action: (ByteArray, ByteArray?) -> Unit) {
        entries.forEach { (key, value) -> action(key.bytes, value) }
    }

    /** Sum of key and value bytes. The engine adds its own per-entry overhead on top. */
    fun byteSize(): Long {
        var total = 0L
        entries.forEach { (key, value) -> total += key.size + (value?.size ?: 0) }
        return total
    }

    private fun journal(key: Key) {
        if (!journaling) return
        undoKeys += key
        undoValues += if (entries.containsKey(key)) entries[key] else ABSENT
    }

    private fun truncate(mark: Int) {
        while (undoKeys.size > mark) {
            undoKeys.removeAt(undoKeys.size - 1)
            undoValues.removeAt(undoValues.size - 1)
        }
        if (mark == 0) journaling = false
    }

    private companion object {
        val ABSENT = ByteArray(0)
    }
}
