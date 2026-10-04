package com.tracel.plugin.util

/** A set of longs without a boxed `Long` per element: open addressing over a primitive array. */
internal class LongHashSet(expected: Int = 16) {
    private var keys: LongArray
    private var used: BooleanArray
    private var mask: Int

    var size: Int = 0; private set

    init {
        var capacity = 16
        while (capacity < expected * 2) capacity = capacity shl 1
        keys = LongArray(capacity)
        used = BooleanArray(capacity)
        mask = capacity - 1
    }

    /** @return `true` if [value] was not in the set before. */
    fun add(value: Long): Boolean {
        if ((size + 1) * 2 > keys.size) grow()
        var slot = slotOf(value)
        while (used[slot]) {
            if (keys[slot] == value) return false
            slot = (slot + 1) and mask
        }
        keys[slot] = value
        used[slot] = true
        size++
        return true
    }

    /** @return `true` if [value] was in the set before. */
    operator fun contains(value: Long): Boolean {
        var slot = slotOf(value)
        while (used[slot]) {
            if (keys[slot] == value) return true
            slot = (slot + 1) and mask
        }
        return false
    }

    /** Adds [value] to the set. */
    operator fun plusAssign(value: Long) {
        add(value)
    }

    private fun slotOf(value: Long): Int {
        var h = value * -7046029254386353131L
        h = h xor (h ushr 29)
        return (h xor (h ushr 32)).toInt() and mask
    }

    private fun grow() {
        val oldKeys = keys
        val oldUsed = used
        keys = LongArray(oldKeys.size shl 1)
        used = BooleanArray(oldKeys.size shl 1)
        mask = keys.size - 1
        size = 0
        for (i in oldKeys.indices) if (oldUsed[i]) add(oldKeys[i])
    }
}
