package com.tracel.storage

import com.tracel.storage.ffm.Key
import com.tracel.storage.spi.EngineCursor
import com.tracel.storage.spi.EngineSnapshot
import com.tracel.storage.spi.MutationBatch
import java.lang.foreign.MemorySegment

/**
 * One unit of storage work: a snapshot to read from and a batch to write into, committed
 * together or not at all.
 */
class StorageUnit(
    private val snapshot: EngineSnapshot,
    val batch: MutationBatch,
    val ownerThread: Thread,
) : AutoCloseable {
    fun get(key: ByteArray): MemorySegment? {
        val wrapped = Key(key)
        if (batch.touches(wrapped)) return batch.valueOf(wrapped)?.let(MemorySegment::ofArray)
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

    fun scan(prefix: ByteArray, from: ByteArray = prefix): EngineCursor =
        OverlayCursor(batch, snapshot.scan(prefix, from), prefix, from)

    override fun close() {
        snapshot.close()
    }
}

private class OverlayCursor(
    batch: MutationBatch,
    private val committed: EngineCursor,
    private val prefix: ByteArray,
    from: ByteArray,
) : EngineCursor {
    private val pending = batch.from(Key(from))
    private var pendingKey: Key? = null
    private var pendingValue: ByteArray? = null
    private var committedValid = committed.next()

    private var currentKey: ByteArray? = null
    private var currentValue: MemorySegment? = null

    init {
        advancePending()
    }

    override fun next(): Boolean {
        while (true) {
            val overlay = pendingKey
            if (overlay == null && !committedValid) return finish()

            if (overlay == null) {
                currentKey = committed.key()
                currentValue = committed.value()
                committedValid = committed.next()
                return true
            }

            if (!committedValid) {
                val value = pendingValue
                val key = overlay.bytes
                advancePending()
                if (value != null) {
                    currentKey = key
                    currentValue = MemorySegment.ofArray(value)
                    return true
                }
                continue
            }

            val order = overlay.compareTo(Key(committed.key()))
            when {
                order < 0 -> {
                    val value = pendingValue
                    val key = overlay.bytes
                    advancePending()
                    if (value != null) {
                        currentKey = key
                        currentValue = MemorySegment.ofArray(value)
                        return true
                    }
                }

                order > 0 -> {
                    currentKey = committed.key()
                    currentValue = committed.value()
                    committedValid = committed.next()
                    return true
                }

                else -> {
                    val value = pendingValue
                    val key = overlay.bytes
                    advancePending()
                    committedValid = committed.next()
                    if (value != null) {
                        currentKey = key
                        currentValue = MemorySegment.ofArray(value)
                        return true
                    }
                }
            }
        }
    }

    override fun key(): ByteArray = currentKey ?: error("cursor is not positioned")

    override fun value(): MemorySegment = currentValue ?: error("cursor is not positioned")

    override fun close() {
        committed.close()
    }

    private fun advancePending() {
        while (pending.hasNext()) {
            val entry = pending.next()
            if (!entry.key.startsWith(prefix)) break
            pendingKey = entry.key
            pendingValue = entry.value
            return
        }
        pendingKey = null
        pendingValue = null
    }

    private fun finish(): Boolean {
        currentKey = null
        currentValue = null
        return false
    }
}
