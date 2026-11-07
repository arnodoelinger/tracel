package com.tracel.storage.chrono.capsule

import com.tracel.storage.chrono.Rows
import com.tracel.storage.chrono.bits.BitPack
import com.tracel.storage.chrono.bits.indexWidth
import com.tracel.storage.chrono.hash.IntIndex
import java.lang.Long.compareUnsigned as compareUnsignedLongs

/** Entity rows into an entity capsule, as [EntityCapsule] lays it out. */
class EntityCapsuleEncoder(magic: Int) : AbstractCapsuleEncoder(CapsuleKind.ENTITIES, magic) {
    private val types = IntIndex(16)
    private val actors = IntIndex(16)
    private val kinds = IntIndex(16)
    private val cells = IntIndex(64)

    override fun clear() {
        types.clear(); actors.clear(); kinds.clear(); cells.clear()
    }

    override fun write(rows: Rows, order: IntArray, extent: Extent) {
        val n = order.size
        val high = requireNotNull(rows.uuidHigh) { "entity rows carry a uuid." }
        val low = requireNotNull(rows.uuidLow) { "entity rows carry a uuid." }
        val cellIdx = IntArray(n)
        val typeIdx = IntArray(n)
        val actorIdx = IntArray(n)
        val kindIdx = IntArray(n)
        for (k in 0 until n) {
            val r = order[k]
            cellIdx[k] = cells.add(rows.cell[r])
            typeIdx[k] = types.add(rows.after[r])
            actorIdx[k] = actors.add(rows.actor[r])
            kindIdx[k] = kinds.add(rows.kind[r])
        }
        val byUuid = (0 until n).sortedWith { a, b ->
            val ra = order[a]
            val rb = order[b]
            val c = compareUnsignedLongs(high[ra], high[rb])
            if (c != 0) c else compareUnsignedLongs(low[ra], low[rb])
        }
        val uuidRank = IntArray(n)
        val uniqueHigh = LongArray(n)
        val uniqueLow = LongArray(n)
        var uuids = 0
        for (j in byUuid.indices) {
            val r = order[byUuid[j]]
            if (uuids == 0 || uniqueHigh[uuids - 1] != high[r] || uniqueLow[uuids - 1] != low[r]) {
                uniqueHigh[uuids] = high[r]
                uniqueLow[uuids] = low[r]
                uuids++
            }
            uuidRank[byUuid[j]] = uuids - 1
        }
        val cellRank = cells.ranked()
        val typeRank = types.ranked()
        val actorRank = actors.ranked()
        val kindRank = kinds.ranked()

        writeHeader(0, rows, order, extent, cells.size, uuids)

        mark(CapsuleSection.SUBJECTS)
        writePalette(typeRank.sorted)
        mark(CapsuleSection.ACTORS)
        writePalette(actorRank.sorted)
        mark(CapsuleSection.KINDS)
        writePalette(kindRank.sorted)
        mark(CapsuleSection.CELLS)
        writeCellTable(cellRank.sorted, extent)

        mark(CapsuleSection.ROWS)
        val column = IntArray(n)
        for (k in 0 until n) column[k] = cellRank.rank[cellIdx[k]]
        BitPack.ints(sink, column, n, indexWidth(cells.size))
        writeTimeline(rows, order, extent)
        for (k in 0 until n) column[k] = actorRank.rank[actorIdx[k]]
        BitPack.ints(sink, column, n, indexWidth(actors.size))
        for (k in 0 until n) column[k] = kindRank.rank[kindIdx[k]]
        BitPack.ints(sink, column, n, indexWidth(kinds.size))
        for (k in 0 until n) column[k] = typeRank.rank[typeIdx[k]]
        BitPack.ints(sink, column, n, indexWidth(types.size))
        BitPack.ints(sink, uuidRank, n, indexWidth(uuids))

        mark(CapsuleSection.TAIL)
        for (u in 0 until uuids) {
            sink.u64(uniqueHigh[u])
            sink.u64(uniqueLow[u])
        }
        ShapeBlobs.write(sink, rows, order)
    }
}
