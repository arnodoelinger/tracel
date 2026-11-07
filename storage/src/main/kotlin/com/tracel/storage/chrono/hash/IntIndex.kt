package com.tracel.storage.chrono.hash

/** Int keys to dense indexes, numbered in the order they were first seen: the palette builder. */
class IntIndex(expected: Int = 16) {
    private var mask = tableSize(expected) - 1
    private var keys = IntArray(mask + 1)
    private var slots = IntArray(mask + 1)
    private var order = IntArray(maxOf(expected, 4))

    var size: Int = 0; private set

    /** Sorted keys and, by index, where each key landed among them. */
    class Ranked(val sorted: IntArray, val rank: IntArray)

    /** The index of [key], or -1. */
    fun indexOf(key: Int): Int {
        var at = mix(key) and mask
        while (true) {
            val slot = slots[at]
            if (slot == 0) return -1
            if (keys[at] == key) return slot - 1
            at = (at + 1) and mask
        }
    }

    /** The index of [key], numbering it next if it is new. */
    fun add(key: Int): Int {
        var at = mix(key) and mask
        while (true) {
            val slot = slots[at]
            if (slot == 0) break
            if (keys[at] == key) return slot - 1
            at = (at + 1) and mask
        }
        val index = size++
        keys[at] = key
        slots[at] = index + 1
        if (index == order.size) order = order.copyOf(order.size shl 1)
        order[index] = key
        if (size * 2 > mask + 1) grow()
        return index
    }

    /**
     * The keys in ascending order, and for every index the place its key took in that order.
     *
     * One sort of longs does both: the key in the high half keeps signed order,
     * the index rides along in the low half.
     */
    fun ranked(): Ranked {
        val packed = LongArray(size) { (order[it].toLong() shl 32) or it.toLong() }
        packed.sort()
        val sorted = IntArray(size)
        val rank = IntArray(size)
        for (r in packed.indices) {
            sorted[r] = (packed[r] shr 32).toInt()
            rank[packed[r].toInt()] = r
        }
        return Ranked(sorted, rank)
    }

    /**
     * Forget everything, so the next [add] starts at zero again.
     *
     * The [ranked] order is lost.
     */
    fun clear() {
        slots.fill(0)
        size = 0
    }

    private fun grow() {
        val oldKeys = keys
        val oldSlots = slots
        mask = (mask shl 1) or 1
        keys = IntArray(mask + 1)
        slots = IntArray(mask + 1)
        for (i in oldSlots.indices) {
            val slot = oldSlots[i]
            if (slot == 0) continue
            var at = mix(oldKeys[i]) and mask
            while (slots[at] != 0) at = (at + 1) and mask
            keys[at] = oldKeys[i]
            slots[at] = slot
        }
    }

    private companion object {
        fun tableSize(expected: Int): Int = Integer.highestOneBit(maxOf(expected, 4) * 2 - 1) shl 1
    }
}

/** Murmur3's finalizer. */
internal fun mix(key: Int): Int {
    var h = key
    h = h xor (h ushr 16)
    h *= -0x7a143595
    h = h xor (h ushr 13)
    h *= -0x3d4d51cb
    return h xor (h ushr 16)
}

/** The 64-bit finalizer, for long keys. */
internal fun mix(key: Long): Int {
    var h = key
    h = h xor (h ushr 33)
    h *= -0xae502812aa7333L
    h = h xor (h ushr 33)
    h *= -0x3b314601e57a13adL
    return (h xor (h ushr 33)).toInt()
}
