package com.tracel.storage.util

/** A set of non-negative longs that does not box them: a day of a busy server's sequence numbers fits in a few MiB. */
internal class LongSet(expected: Int = 16) {
    private var table = LongArray(capacityFor(expected)).also { it.fill(EMPTY) }

    var size: Int = 0; private set

    /** Adds [value] to the set, ignoring it if already present. */
    fun add(value: Long) {
        require(value >= 0) { "$value is not a sequence number" }
        if ((size + 1) * 2 > table.size) grow()
        if (insert(table, value)) size++
    }

    /** Removes [value] from the set, ignoring it if not present. */
    operator fun contains(value: Long): Boolean {
        if (value < 0 || size == 0) return false
        var at = slot(value, table.size)
        while (true) {
            val found = table[at]
            if (found == EMPTY) return false
            if (found == value) return true
            at = (at + 1) and (table.size - 1)
        }
    }

    private fun grow() {
        val bigger = LongArray(table.size * 2).also { it.fill(EMPTY) }
        for (value in table) if (value != EMPTY) insert(bigger, value)
        table = bigger
    }

    private fun insert(into: LongArray, value: Long): Boolean {
        var at = slot(value, into.size)
        while (true) {
            val found = into[at]
            if (found == EMPTY) {
                into[at] = value
                return true
            }
            if (found == value) return false
            at = (at + 1) and (into.size - 1)
        }
    }

    private fun slot(value: Long, length: Int): Int {
        var h = value * -0x61c8864680b583ebL
        h = h xor (h ushr 32)
        return h.toInt() and (length - 1)
    }

    private companion object {
        const val EMPTY = Long.MIN_VALUE

        fun capacityFor(expected: Int): Int {
            var n = 16
            while (n < expected * 2) n = n shl 1
            return n
        }
    }
}
