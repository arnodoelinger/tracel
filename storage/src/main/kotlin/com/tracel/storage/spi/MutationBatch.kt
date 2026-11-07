package com.tracel.storage.spi

import com.tracel.storage.ffm.Key
import java.util.*

/**
 * An ordered set of puts and deletes, applied as one. The last write to a key wins;
 * a delete is a write of `null`.
 *
 * Writes can be taken back: [mark] starts journaling, [rollbackTo] undoes everything
 * after a mark, [release] keeps it and forgets the journal.
 *
 * A [putPinned] is never journaled, so no rollback takes it away.
 *
 * Not thread-safe: one thread writes it, and readers on other threads are fine only
 * while nothing does.
 */
class MutationBatch {
    private val entries = HashMap<Key, ByteArray?>()
    private val ordered = TreeSet<Key>()
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
