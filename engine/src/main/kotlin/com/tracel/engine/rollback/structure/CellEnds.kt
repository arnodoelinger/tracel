package com.tracel.engine.rollback.structure

import com.tracel.model.id.WorldId
import com.tracel.model.world.BlockPos
import com.tracel.model.world.WorldChange

private const val XZ_LIMIT = 1 shl 25
private const val Y_LIMIT = 1 shl 11

private const val MIN_CAPACITY = 16

private const val MAX_PRESIZE = 1 shl 16

private const val FREE = -1

private fun fits(at: BlockPos): Boolean =
    at.x >= -XZ_LIMIT && at.x < XZ_LIMIT && at.z >= -XZ_LIMIT && at.z < XZ_LIMIT && at.y >= -Y_LIMIT && at.y < Y_LIMIT

private fun pack(at: BlockPos): Long =
    ((at.x.toLong() and 0x3FFFFFF) shl 38) or ((at.z.toLong() and 0x3FFFFFF) shl 12) or (at.y.toLong() and 0xFFF)

private fun hash(key: Long): Int {
    var h = key * -7046029254386353131L
    h = h xor (h ushr 29)
    return (h xor (h ushr 32)).toInt()
}

internal class CellEnds(changes: List<WorldChange>) {
    private val changes: List<WorldChange> = if (changes is RandomAccess) changes else ArrayList(changes)
    private val worlds = LinkedHashMap<WorldId, Cells>()
    private val far = LinkedHashMap<BlockPos, IntArray>()
    private var lastWorld: WorldId? = null
    private var lastCells: Cells? = null

    private companion object {
        const val OLDEST = 0
        const val NEWEST = 1
    }

    fun note(index: Int) {
        val at = changes[index].at
        if (!fits(at)) {
            val ends = far.getOrPut(at) { intArrayOf(index, index) }
            if (newer(index, ends[NEWEST])) ends[NEWEST] = index
            if (older(index, ends[OLDEST])) ends[OLDEST] = index
            return
        }
        cellsOf(at.world).note(pack(at), index)
    }

    fun forEach(visit: (oldest: WorldChange, newest: WorldChange) -> Unit) {
        for (cells in worlds.values) cells.forEach { oldest, newest -> visit(changes[oldest], changes[newest]) }
        for (ends in far.values) visit(changes[ends[OLDEST]], changes[ends[NEWEST]])
    }

    private fun cellsOf(world: WorldId): Cells {
        val last = lastCells
        if (last != null && world == lastWorld) return last
        val cells = worlds.getOrPut(world) { Cells(changes.size) }
        lastWorld = world
        lastCells = cells
        return cells
    }

    private fun newer(index: Int, than: Int): Boolean = changes[index].seq.raw > changes[than].seq.raw

    private fun older(index: Int, than: Int): Boolean = changes[index].seq.raw < changes[than].seq.raw

    private inner class Cells(expected: Int) {
        private var keys: LongArray
        private var oldest: IntArray
        private var newest: IntArray
        private var order: IntArray
        private var mask: Int
        private var size = 0

        init {
            var capacity = MIN_CAPACITY
            while (capacity < expected.coerceAtMost(MAX_PRESIZE) * 2) capacity = capacity shl 1
            keys = LongArray(capacity)
            oldest = IntArray(capacity) { FREE }
            newest = IntArray(capacity)
            order = IntArray(capacity / 2)
            mask = capacity - 1
        }

        fun note(key: Long, index: Int) {
            if ((size + 1) * 2 > keys.size) grow()
            var slot = hash(key) and mask
            while (oldest[slot] != FREE) {
                if (keys[slot] == key) {
                    if (newer(index, newest[slot])) newest[slot] = index
                    if (older(index, oldest[slot])) oldest[slot] = index
                    return
                }
                slot = (slot + 1) and mask
            }
            keys[slot] = key
            oldest[slot] = index
            newest[slot] = index
            if (size == order.size) order = order.copyOf(size * 2)
            order[size++] = slot
        }

        fun forEach(visit: (oldest: Int, newest: Int) -> Unit) {
            for (i in 0 until size) visit(oldest[order[i]], newest[order[i]])
        }

        private fun grow() {
            val oldKeys = keys
            val oldOldest = oldest
            val oldNewest = newest
            val capacity = keys.size shl 1
            keys = LongArray(capacity)
            oldest = IntArray(capacity) { FREE }
            newest = IntArray(capacity)
            mask = capacity - 1
            for (i in 0 until size) {
                val from = order[i]
                var slot = hash(oldKeys[from]) and mask
                while (oldest[slot] != FREE) slot = (slot + 1) and mask
                keys[slot] = oldKeys[from]
                oldest[slot] = oldOldest[from]
                newest[slot] = oldNewest[from]
                order[i] = slot
            }
        }
    }
}
