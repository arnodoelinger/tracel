package com.tracel.storage.lsm.read

import com.tracel.storage.lsm.InternalKey
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandles
import java.lang.invoke.VarHandle
import java.nio.ByteOrder

/**
 * One sorted run positioned somewhere in itself. A memtable or a segment file, seen through
 * the same three questions the merge asks: where are you, how new are you, what do you hold.
 */
internal abstract class Run {
    @JvmField var valid: Boolean = false

    @JvmField var keySegment: MemorySegment = MemorySegment.NULL
    @JvmField var keyOffset: Long = 0
    @JvmField var keyLength: Int = 0

    @JvmField var userKeyLength: Int = 0

    abstract fun seek(internalKey: ByteArray, length: Int)
    abstract fun next()
    abstract fun sequence(): Long
    abstract fun isDeletion(): Boolean
    abstract fun value(): MemorySegment?
    abstract fun userKeyBytes(): ByteArray

    /** Copies the first [userKeyLength] bytes of the user key into [dst]. */
    fun userKeyInto(dst: ByteArray) {
        MemorySegment.copy(keySegment, ValueLayout.JAVA_BYTE, keyOffset, dst, 0, userKeyLength)
    }

    /** Seeks to the first key that is greater than or equal to [internalKey]. */
    fun seek(internalKey: ByteArray) = seek(internalKey, internalKey.size)

    private var past = ByteArray(48)

    /** Jumps past every remaining version of the first [userKeyLength] bytes of [userKey]. */
    fun skipPast(userKey: ByteArray, userKeyLength: Int) {
        val length = userKeyLength + InternalKey.TRAILER_BYTES
        var buffer = past
        if (buffer.size < length) {
            buffer = ByteArray(length + 32)
            past = buffer
        }
        System.arraycopy(userKey, 0, buffer, 0, userKeyLength)
        BE_LONG.set(buffer, userKeyLength, -1L)
        seek(buffer, length)
    }

    companion object {
        private val BE_LONG: VarHandle =
            MethodHandles.byteArrayViewVarHandle(LongArray::class.java, ByteOrder.BIG_ENDIAN)
    }
}
