package com.tracel.storage.chrono.capsule

import com.tracel.storage.chrono.Lane
import com.tracel.storage.chrono.Rows
import com.tracel.storage.chrono.bits.BitUnpack
import com.tracel.storage.chrono.bits.SegmentReader
import com.tracel.storage.chrono.bits.indexWidth
import com.tracel.storage.chrono.bits.packedBytes
import java.lang.foreign.MemorySegment

/** Entity capsule. */
class EntityCapsule(bytes: MemorySegment, lane: Lane = Lane.ENTITIES) :
    AbstractCapsule(bytes, lane, CapsuleKind.ENTITIES, extraCounts = 1) {
    val uuidCount: Int get() = counts[0]

    val types: IntArray by lazy { palette(CapsuleSection.SUBJECTS) }

    private val layout: Layout by lazy { Layout() }

    override val timeline: Timeline get() = layout.timeline

    override fun rows(): Rows {
        val n = rowCount
        val l = layout
        val keys = cells
        val cell = IntArray(n).also { BitUnpack.mapped(bytes, l.cellAt, indexWidth(cellCount), n, keys, it) }
        val actor = IntArray(n).also { BitUnpack.mapped(bytes, l.actorAt, indexWidth(actors.size), n, actors, it) }
        val kind = IntArray(n).also { BitUnpack.mapped(bytes, l.kindAt, indexWidth(kinds.size), n, kinds, it) }
        val type = IntArray(n).also { BitUnpack.mapped(bytes, l.typeAt, indexWidth(types.size), n, types, it) }
        val uuidIdx = IntArray(n).also { BitUnpack.ints(bytes, l.uuidAt, indexWidth(uuidCount), n, it) }
        val c = SegmentReader(bytes, offset(CapsuleSection.TAIL))
        val uh = LongArray(uuidCount)
        val ul = LongArray(uuidCount)
        for (u in 0 until uuidCount) {
            uh[u] = c.u64()
            ul[u] = c.u64()
        }
        val shapes = ShapeBlobs.read(bytes, c, n)
        val before = Array(n) { shapes[it] }
        val after = Array(n) { shapes[n + it] }
        return Rows(
            n, seqs(), millis(), cell, actor, kind, type, type.copyOf(), before, after,
            LongArray(n) { uh[uuidIdx[it]] }, LongArray(n) { ul[uuidIdx[it]] },
        )
    }

    private inner class Layout {
        val cellAt: Long
        val timeline: Timeline
        val actorAt: Long
        val kindAt: Long
        val typeAt: Long
        val uuidAt: Long

        init {
            val tail = offset(CapsuleSection.TAIL)
            val r = SegmentReader(bytes, offset(CapsuleSection.ROWS))
            cellAt = skipCellColumn(r)
            timeline = Timeline.read(bytes, r, rowCount)
            actorAt = r.at
            kindAt = actorAt + packedBytes(rowCount, indexWidth(actors.size))
            typeAt = kindAt + packedBytes(rowCount, indexWidth(kinds.size))
            uuidAt = typeAt + packedBytes(rowCount, indexWidth(types.size))
            check(
                uuidAt + packedBytes(
                    rowCount,
                    indexWidth(uuidCount)
                ) == tail
            ) { "entity capsule rows end past the tail at $tail." }
        }
    }

    companion object {
        fun of(bytes: ByteArray): EntityCapsule = EntityCapsule(MemorySegment.ofArray(bytes))
    }
}
