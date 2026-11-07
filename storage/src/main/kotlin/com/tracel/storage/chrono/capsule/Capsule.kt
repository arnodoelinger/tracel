package com.tracel.storage.chrono.capsule

import com.tracel.storage.chrono.Lane
import com.tracel.storage.chrono.Rows
import com.tracel.storage.chrono.bits.BitUnpack
import com.tracel.storage.chrono.bits.SegmentReader
import com.tracel.storage.chrono.bits.indexWidth
import java.lang.foreign.MemorySegment

/** A block capsule. */
class Capsule(bytes: MemorySegment) : AbstractCapsule(bytes, Lane.BLOCKS, CapsuleKind.BLOCKS) {
    private val cellExtras: Extras by lazy { extrasOf(rowsToo = false) }
    private val extras: Extras by lazy { extrasOf(rowsToo = true) }

    override val timeline: Timeline get() = layout.timeline

    val hasExtras: Boolean get() = flags and FLAG_EXTRAS != 0
    val states: IntArray by lazy { palette(CapsuleSection.SUBJECTS) }
    val layout: Layout by lazy { Layout() }

    /** The state every cell held before this capsule's first change to it, by cell index. */
    fun entries(): IntArray = IntArray(cellCount).also { BitUnpack.mapped(bytes, layout.entryAt, layout.stateBits, cellCount, states, it) }

    /** The state every cell held after this capsule's last change to it, by cell index. */
    fun exits(): IntArray {
        val cell = cellIndexes()
        val after = afterIndexes()
        val palette = states
        val out = IntArray(cellCount)
        for (k in 0 until rowCount) out[cell[k]] = palette[after[k]]
        return out
    }

    fun entryExtras(cellIndex: Int): ByteArray? = if (hasExtras) cellExtras.entry?.get(cellIndex) else null

    fun exitExtras(cellIndex: Int): ByteArray? = if (hasExtras) cellExtras.exit?.get(cellIndex) else null

    /** The cell index of every row. */
    fun cellIndexes(): IntArray {
        val cumulative = layout.cell.cumulative(bytes, rowCount, layout.cellFirst, signed = true)
        return IntArray(rowCount) { cumulative[it].toInt() }
    }

    /** The palette index of every row's cause. */
    fun actorIndexes(): IntArray = layout.actor.decode(bytes, rowCount)

    /** The palette index of every row's kind. */
    fun kindIndexes(): IntArray = layout.kind.decode(bytes, rowCount)

    /** The palette index of the state every row left its cell in. */
    fun afterIndexes(): IntArray = layout.after.decode(bytes, rowCount)

    /**
     * Where the state chain broke: row index in sequence order,
     * and the state the row really started from.
     */
    fun breaks(): Breaks = ChainTail.readBreaks(bytes, offset(CapsuleSection.TAIL), states)

    override fun rows(): Rows {
        val n = rowCount
        val cellIdx = cellIndexes()
        val cell = IntArray(n)
        val keys = cells
        for (k in 0 until n) cell[k] = keys[cellIdx[k]]
        val actor = actorIndexes().let { idx -> val p = actors; IntArray(n) { p[idx[it]] } }
        val kind = kindIndexes().let { idx -> val p = kinds; IntArray(n) { p[idx[it]] } }
        val after = afterIndexes().let { idx -> val p = states; IntArray(n) { p[idx[it]] } }
        val before = IntArray(n)
        val cur = entries()
        val brk = breaks()
        var nextBreak = 0
        val ex = if (hasExtras) extras else null
        val beforeExtras = if (ex != null) arrayOfNulls<ByteArray>(n) else null
        val afterExtras = if (ex != null) arrayOfNulls<ByteArray>(n) else null
        val curExtras = if (ex != null) arrayOfNulls<ByteArray>(cellCount).also { ex.entry?.copyInto(it) } else null
        for (k in 0 until n) {
            val c = cellIdx[k]
            if (nextBreak < brk.size && brk.rows[nextBreak] == k) {
                before[k] = brk.states[nextBreak]
                beforeExtras?.set(k, ex?.breaks?.get(nextBreak))
                nextBreak++
            } else {
                before[k] = cur[c]
                beforeExtras?.set(k, curExtras?.get(c))
            }
            cur[c] = after[k]
            if (ex != null) {
                val ae = ex.after?.get(k)
                afterExtras?.set(k, ae)
                curExtras?.set(c, ae)
            }
        }
        return Rows(n, seqs(), millis(), cell, actor, kind, before, after, beforeExtras, afterExtras)
    }

    private fun extrasOf(rowsToo: Boolean): Extras =
        if (!hasExtras) Extras.NONE else ChainTail.readExtras(bytes, offset(CapsuleSection.TAIL), cellCount, rowCount, rowsToo)

    inner class Layout internal constructor() {
        internal val timeline: Timeline

        val stateBits: Int = indexWidth(states.size)
        val cellBits: Int = indexWidth(cellCount)
        val actorBits: Int = indexWidth(actors.size)
        val kindBits: Int = indexWidth(kinds.size)

        val entryAt: Long = cellTable.second

        val cellFirst: Long
        val cell: Parts.Gaps
        val actor: Parts.Indexes
        val kind: Parts.Indexes
        val after: Parts.Indexes

        init {
            val tail = offset(CapsuleSection.TAIL)
            val r = SegmentReader(bytes, offset(CapsuleSection.ROWS))
            cellFirst = r.varint()
            cell = Parts.readGaps(r, rowCount)
            timeline = Timeline.read(bytes, r, rowCount)
            actor = Parts.readIndexes(r, rowCount, actorBits)
            kind = Parts.readIndexes(r, rowCount, kindBits)
            after = Parts.readIndexes(r, rowCount, stateBits)
            check(after.end == tail) { "capsule rows end at ${after.end}, the tail starts at $tail." }
        }
    }

    companion object {
        const val FLAG_EXTRAS: Int = 1

        fun of(bytes: ByteArray): Capsule = Capsule(MemorySegment.ofArray(bytes))
    }
}
