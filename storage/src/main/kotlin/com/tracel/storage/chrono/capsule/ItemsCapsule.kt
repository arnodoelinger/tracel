package com.tracel.storage.chrono.capsule

import com.tracel.storage.chrono.Lane
import com.tracel.storage.chrono.Rows
import com.tracel.storage.chrono.bits.BitUnpack
import com.tracel.storage.chrono.bits.SegmentReader
import com.tracel.storage.chrono.bits.indexWidth
import com.tracel.storage.chrono.bits.packedBytes
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout

/** Items capsule. */
class ItemsCapsule(bytes: MemorySegment) : AbstractCapsule(bytes, Lane.ITEMS, CapsuleKind.ITEMS) {
    val items: IntArray by lazy { palette(CapsuleSection.SUBJECTS) }

    override val trackIds: IntArray by lazy { IntArray(actors.size + items.size) { if (it < actors.size) actors[it] else -items[it - actors.size] } }

    private val layout: Layout by lazy { Layout() }

    override val timeline: Timeline get() = layout.timeline

    override fun rows(): Rows {
        val n = rowCount
        val l = layout
        val holderBits = indexWidth(actors.size)
        val cell = IntArray(n).also { BitUnpack.mapped(bytes, l.cellAt, indexWidth(cellCount), n, cells, it) }
        val actor = IntArray(n).also { BitUnpack.mapped(bytes, l.actorAt, holderBits, n, actors, it) }
        val kind = IntArray(n).also { BitUnpack.mapped(bytes, l.kindAt, indexWidth(kinds.size), n, kinds, it) }
        val item = IntArray(n).also { BitUnpack.mapped(bytes, l.itemAt, indexWidth(items.size), n, items, it) }
        val source = IntArray(n).also { BitUnpack.mapped(bytes, l.sourceAt, holderBits, n, actors, it) }
        val destination = IntArray(n).also { BitUnpack.mapped(bytes, l.destinationAt, holderBits, n, actors, it) }
        val flow = IntArray(n).also { BitUnpack.ints(bytes, l.flowAt, l.flowBits, n, it) }
        val quantity = l.quantity.values(bytes, n)
        val txn = l.txn.values(bytes, n)
        val homes = Array(3) { c -> l.home[c].values(bytes, n).let { v -> IntArray(n) { v[it].toInt() } } }
        val c = SegmentReader(bytes, offset(CapsuleSection.TAIL))
        val lengths = IntArray(n) { c.varintInt() }
        val lots = arrayOfNulls<ByteArray>(n)
        for (k in 0 until n) if (lengths[k] > 0) lots[k] = c.bytes(lengths[k])
        return Rows(
            n,
            seqs(),
            millis(),
            cell,
            actor,
            kind,
            item,
            source,
            lots,
            null,
            quantity,
            txn,
            arrayOf(destination, flow, homes[0], homes[1], homes[2])
        )
    }

    private inner class Layout {
        val cellAt: Long
        val timeline: Timeline
        val actorAt: Long
        val kindAt: Long
        val itemAt: Long
        val sourceAt: Long
        val destinationAt: Long
        val flowAt: Long
        val flowBits: Int
        val quantity: Parts.Gaps
        val txn: Parts.Gaps
        val home: Array<Parts.Gaps>

        init {
            val r = SegmentReader(bytes, offset(CapsuleSection.ROWS))
            cellAt = skipCellColumn(r)
            timeline = Timeline.read(bytes, r, rowCount)
            val holderBits = indexWidth(actors.size)
            actorAt = r.at
            kindAt = actorAt + packedBytes(rowCount, holderBits)
            itemAt = kindAt + packedBytes(rowCount, indexWidth(kinds.size))
            sourceAt = itemAt + packedBytes(rowCount, indexWidth(items.size))
            destinationAt = sourceAt + packedBytes(rowCount, holderBits)
            flowAt = destinationAt + packedBytes(rowCount, holderBits) + 1
            flowBits = bytes.get(ValueLayout.JAVA_BYTE, flowAt - 1).toInt() and 0xFF
            r.at = flowAt + packedBytes(rowCount, flowBits)
            quantity = Parts.readFramed(r, rowCount)
            txn = Parts.readFramed(r, rowCount)
            home = Array(3) { Parts.readFramed(r, rowCount) }
            check(home[2].end == offset(CapsuleSection.TAIL)) {
                "items capsule rows end at ${home[2].end}, the tail starts at ${
                    offset(
                        CapsuleSection.TAIL
                    )
                }."
            }
        }
    }

    companion object {
        fun of(bytes: ByteArray): ItemsCapsule = ItemsCapsule(MemorySegment.ofArray(bytes))
    }
}
