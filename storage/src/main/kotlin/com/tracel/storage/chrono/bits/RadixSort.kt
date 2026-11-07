package com.tracel.storage.chrono.bits

import java.util.*

/** Sorter. */
object RadixSort {
    /** The order that puts the first [n] of [keys] in ascending order, ties kept in place. */
    fun ascending(keys: LongArray, n: Int): IntArray {
        var order = IntArray(n) { it }
        if (n < 2) return order
        var src = keys.copyOf(n)
        var dst = LongArray(n)
        var orderInto = IntArray(n)
        val counts = IntArray(257)
        for (shift in 0 until 64 step 8) {
            Arrays.fill(counts, 0)
            val flip = if (shift == 56) 0x80 else 0
            for (i in 0 until n) counts[(((src[i] ushr shift).toInt() and 0xFF) xor flip) + 1]++
            var nonEmpty = 0
            for (b in 1..256) if (counts[b] != 0) nonEmpty++
            if (nonEmpty <= 1) continue
            for (b in 1..256) counts[b] += counts[b - 1]
            for (i in 0 until n) {
                val at = counts[((src[i] ushr shift).toInt() and 0xFF) xor flip]++
                dst[at] = src[i]
                orderInto[at] = order[i]
            }
            src = dst.also { dst = src }
            order = orderInto.also { orderInto = order }
        }
        return order
    }
}
