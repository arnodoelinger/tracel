package com.tracel.storage.lsm.read

import com.tracel.storage.ffm.Bytes.i8
import com.tracel.storage.ffm.SegmentCompare
import com.tracel.storage.lsm.InternalKey
import com.tracel.storage.lsm.segment.SegmentReader
import com.tracel.storage.lsm.segment.Varints
import java.lang.foreign.MemorySegment
import java.lang.invoke.MethodHandles
import java.lang.invoke.VarHandle
import java.nio.ByteOrder

internal class SegmentRun(private val reader: SegmentReader) : Run() {
    private val fileEnd = reader.end()
    private var data: MemorySegment = MemorySegment.NULL
    private var fileOff = 0L
    private var blockEnd = 0L
    private var at = 0L
    private var headerBytes = 0
    private var unshared = 0
    private var valueLength = 0
    private var valueAt = 0L
    private var key = ByteArray(64)
    private var keyMem = MemorySegment.ofArray(key)

    override fun seek(internalKey: ByteArray, length: Int) {
        if (!openBlock(reader.restartAt(internalKey, length))) {
            valid = false
            return
        }
        keyLength = 0
        while (true) {
            if (at >= blockEnd && !openBlock(reader.nextBlock(fileOff))) {
                valid = false
                return
            }
            decode()
            if (SegmentCompare.compare(keyMem, 0, keyLength, internalKey, length) >= 0) {
                valid = true
                return
            }
            at = nextAt()
        }
    }

    override fun next() {
        at = nextAt()
        if (at >= blockEnd && !openBlock(reader.nextBlock(fileOff))) {
            valid = false
            return
        }
        decode()
        valid = true
    }

    override fun sequence(): Long = InternalKey.sequenceOf(BE_LONG.get(key, userKeyLength) as Long)

    override fun isDeletion(): Boolean = valueLength < 0

    override fun value(): MemorySegment? {
        if (valueLength < 0) return null
        return data.asSlice(valueAt, valueLength.toLong())
    }

    override fun userKeyBytes(): ByteArray = key.copyOf(userKeyLength)

    @Suppress("ConvertTwoComparisonsToRangeCheck")
    private fun openBlock(offset: Long): Boolean {
        if (offset < 0 || offset >= fileEnd) return false
        val raw = reader.blockAt(offset)
        if (raw.isEmpty()) return false
        fileOff = offset
        data = MemorySegment.ofArray(raw)
        blockEnd = raw.size.toLong()
        at = 0
        return true
    }

    private fun decode() {
        var cursor = at
        val sharedPacked = Varints.read(data, cursor)
        cursor += Varints.sizeOf(sharedPacked)
        val unsharedPacked = Varints.read(data, cursor)
        cursor += Varints.sizeOf(unsharedPacked)
        val valuePacked = Varints.read(data, cursor)
        cursor += Varints.sizeOf(valuePacked)
        val shared = Varints.valueOf(sharedPacked)
        unshared = Varints.valueOf(unsharedPacked)
        valueLength = Varints.valueOf(valuePacked) - 1
        headerBytes = (cursor - at).toInt()
        val total = shared + unshared
        if (key.size < total) {
            val grown = ByteArray(total + 32)
            System.arraycopy(key, 0, grown, 0, keyLength)
            key = grown
            keyMem = MemorySegment.ofArray(key)
        }
        var i = 0
        while (i < unshared) {
            key[shared + i] = data.i8(cursor + i)
            i++
        }
        keyLength = total
        userKeyLength = total - InternalKey.TRAILER_BYTES
        keySegment = keyMem
        keyOffset = 0
        valueAt = cursor + unshared
    }

    private fun nextAt(): Long = at + headerBytes + unshared + (if (valueLength > 0) valueLength else 0)

    private companion object {
        val BE_LONG: VarHandle =
            MethodHandles.byteArrayViewVarHandle(LongArray::class.java, ByteOrder.BIG_ENDIAN)
    }
}
