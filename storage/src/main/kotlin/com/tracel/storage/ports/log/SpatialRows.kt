package com.tracel.storage.ports.log

import java.util.Arrays

/** The world rows a spatial walk kept, with everything the index itself said about each one. */
internal class SpatialRows(capacity: Int = 64) {
    var size: Int = 0; private set
    var seqs = LongArray(capacity); private set
    var millis = LongArray(capacity); private set
    var xs = IntArray(capacity); private set
    var ys = IntArray(capacity); private set
    var zs = IntArray(capacity); private set
    var beforeIds = IntArray(capacity); private set
    var afterIds = IntArray(capacity); private set
    var causedByIds = IntArray(capacity); private set
    var causes = ByteArray(capacity); private set
    var actions = ByteArray(capacity); private set
    var deferred = BooleanArray(capacity); private set

    fun add(
        seq: Long,
        atMillis: Long,
        x: Int,
        y: Int,
        z: Int,
        cause: Byte,
        action: Byte,
        beforeId: Int,
        afterId: Int,
        causedById: Int,
        needsRecord: Boolean,
    ) {
        if (size == seqs.size) grow()
        val i = size
        seqs[i] = seq; millis[i] = atMillis
        xs[i] = x; ys[i] = y; zs[i] = z
        causes[i] = cause; actions[i] = action
        beforeIds[i] = beforeId; afterIds[i] = afterId; causedByIds[i] = causedById
        deferred[i] = needsRecord
        size = i + 1
    }

    fun addAll(other: SpatialRows) {
        reserve(size + other.size)
        val at = size
        System.arraycopy(other.seqs, 0, seqs, at, other.size)
        System.arraycopy(other.millis, 0, millis, at, other.size)
        System.arraycopy(other.xs, 0, xs, at, other.size)
        System.arraycopy(other.ys, 0, ys, at, other.size)
        System.arraycopy(other.zs, 0, zs, at, other.size)
        System.arraycopy(other.beforeIds, 0, beforeIds, at, other.size)
        System.arraycopy(other.afterIds, 0, afterIds, at, other.size)
        System.arraycopy(other.causedByIds, 0, causedByIds, at, other.size)
        System.arraycopy(other.causes, 0, causes, at, other.size)
        System.arraycopy(other.actions, 0, actions, at, other.size)
        System.arraycopy(other.deferred, 0, deferred, at, other.size)
        size = at + other.size
    }

    private fun grow() = reserve(maxOf(seqs.size shl 1, 64))

    /** Room for [n] rows, in one round of copies rather than a doubling apiece. */
    fun reserve(n: Int) {
        if (seqs.size >= n) return
        seqs = seqs.copyOf(n); millis = millis.copyOf(n)
        xs = xs.copyOf(n); ys = ys.copyOf(n); zs = zs.copyOf(n)
        beforeIds = beforeIds.copyOf(n); afterIds = afterIds.copyOf(n)
        causedByIds = causedByIds.copyOf(n)
        causes = causes.copyOf(n); actions = actions.copyOf(n)
        deferred = deferred.copyOf(n)
    }

    /** Row indices ordered by ascending sequence. */
    fun ascendingOrder(): IntArray {
        val n = size
        var keys = seqs.copyOf(n)
        var order = IntArray(n) { it }
        var keysInto = LongArray(n)
        var orderInto = IntArray(n)
        val counts = IntArray(257)
        for (shift in 0 until 64 step 8) {
            Arrays.fill(counts, 0)
            for (i in 0 until n) counts[(((keys[i] ushr shift) and 0xFF).toInt()) + 1]++
            var carried = 0
            var nonEmpty = 0
            for (b in 1..256) {
                if (counts[b] != 0) nonEmpty++
                carried += counts[b]
                counts[b] = carried
            }
            // Every row shares this byte; the pass would be a straight copy
            if (nonEmpty <= 1) continue
            for (i in 0 until n) {
                val key = keys[i]
                val at = counts[((key ushr shift) and 0xFF).toInt()]++
                keysInto[at] = key
                orderInto[at] = order[i]
            }
            val swapKeys = keys; keys = keysInto; keysInto = swapKeys
            val swapOrder = order; order = orderInto; orderInto = swapOrder
        }
        return order
    }
}
