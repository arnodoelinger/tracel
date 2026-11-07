package com.tracel.storage.chrono.capsule

import com.tracel.storage.chrono.bits.ByteWriter
import com.tracel.storage.chrono.bits.SegmentReader
import java.lang.foreign.MemorySegment

/**
 * The tail of a block capsule: where the state chain broke, then the extras.
 *
 * One writer and one reader of each.
 */
internal object ChainTail {
    /** One blob to write: [index] counts within its [side]. */
    class Extra(val side: ExtraSide, val index: Int, val bytes: ByteArray)

    /** The breaks: how many, then each as the gap in rows and the palette index of the state it started from. */
    fun writeBreaks(sink: ByteWriter, count: Int, rows: IntArray, states: IntArray, rank: IntArray) {
        sink.varint(count.toLong())
        var previous = -1
        for (b in 0 until count) {
            sink.varint((rows[b] - previous - 1).toLong())
            sink.varint(rank[states[b]].toLong())
            previous = rows[b]
        }
    }

    /** The extras, sorted by side and index: a count, then (side, index gap, length, bytes). */
    fun writeExtras(sink: ByteWriter, extras: List<Extra>) {
        sink.varint(extras.size.toLong())
        var side: ExtraSide? = null
        var at = -1
        for (e in extras) {
            if (e.side != side) {
                side = e.side
                at = -1
            }
            sink.u8(e.side.ordinal)
            sink.varint((e.index - at - 1).toLong())
            sink.varint(e.bytes.size.toLong())
            sink.bytes(e.bytes)
            at = e.index
        }
    }

    /**
     * The breaks and extras at [at] in [seg], with the palette rank of the states they refer to.
     *
     * The extras are only the ones that exist, sorted by side and index.
     */
    fun readBreaks(seg: MemorySegment, at: Long, palette: IntArray): Breaks {
        val cursor = SegmentReader(seg, at)
        val n = cursor.varintInt()
        val rows = IntArray(n)
        val states = IntArray(n)
        var previous = -1
        for (b in 0 until n) {
            previous += cursor.varintInt() + 1
            rows[b] = previous
            states[b] = palette[cursor.varintInt()]
        }
        return Breaks(rows, states)
    }

    /** The extras behind the breaks at [at]; with [rowsToo] `false` it stops after the cells', which come first. */
    fun readExtras(seg: MemorySegment, at: Long, cellCount: Int, rowCount: Int, rowsToo: Boolean): Extras {
        val cursor = SegmentReader(seg, at)
        val breaks = cursor.varintInt()
        repeat(breaks) {
            cursor.varint()
            cursor.varint()
        }
        val entry = arrayOfNulls<ByteArray>(cellCount)
        val exit = arrayOfNulls<ByteArray>(cellCount)
        val after = if (rowsToo) arrayOfNulls<ByteArray>(rowCount) else null
        val broken = if (rowsToo) arrayOfNulls<ByteArray>(breaks) else null
        var side = -1
        var index = -1
        repeat(cursor.varintInt()) {
            val code = cursor.u8()
            val which = ExtraSide.entries.getOrNull(code) ?: error("extras on side $code, which no capsule has.")
            if (!rowsToo && !which.isCell) return Extras(entry, exit, null, null)
            if (code != side) {
                side = code
                index = -1
            }
            index += cursor.varintInt() + 1
            val blob = cursor.bytes(cursor.varintInt())
            when (which) {
                ExtraSide.ENTRY -> entry[index] = blob
                ExtraSide.EXIT -> exit[index] = blob
                ExtraSide.AFTER -> after?.set(index, blob)
                ExtraSide.BREAK -> broken?.set(index, blob)
            }
        }
        return Extras(entry, exit, after, broken)
    }
}
