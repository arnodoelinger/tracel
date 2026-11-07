package com.tracel.storage

import com.tracel.storage.ffm.Key
import com.tracel.storage.spi.EngineCursor
import com.tracel.storage.spi.MutationBatch
import java.lang.foreign.MemorySegment
import java.util.*

/**
 * [committed] with [batch] laid over it, in key order: a key the batch wrote shadows the snapshot's,
 * a delete hides it.
 *
 * What [StorageUnit.scan] returns while the unit has written anything.
 */
internal class OverlayCursor(
    private val batch: MutationBatch,
    private val committed: EngineCursor,
    private val prefix: ByteArray,
    from: ByteArray,
    private val to: ByteArray?,
) : EngineCursor {
    private var pending = batch.keysFrom(Key(from))
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

    override fun key(): ByteArray = currentKey ?: error("cursor is not positioned.")

    override fun keyLength(): Int = key().size

    override fun keyByte(at: Int): Byte = key()[at]

    override fun value(): MemorySegment = currentValue ?: error("cursor is not positioned.")

    override fun skipTo(from: ByteArray) {
        committed.skipTo(from)
        committedValid = committed.next()
        pending = batch.keysFrom(Key(from))
        pendingKey = null
        pendingValue = null
        advancePending()
        currentKey = null
        currentValue = null
    }

    override fun close() {
        committed.close()
    }

    private fun advancePending() {
        while (pending.hasNext()) {
            val key = pending.next()
            if (!key.startsWith(prefix)) break
            if (to != null && Arrays.compareUnsigned(key.bytes, to) >= 0) break
            pendingKey = key
            pendingValue = batch.valueOf(key)
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
