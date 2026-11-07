package com.tracel.storage.chrono

/** Changes as columns: row `i` is `seq[i]`, `millis[i]`, `cell[i]` and so on down the line. */
class Rows(
    val size: Int,
    val seq: LongArray,
    val millis: LongArray,
    val cell: IntArray,
    val actor: IntArray,
    val kind: IntArray,
    val before: IntArray,
    val after: IntArray,
    val beforeExtras: Array<ByteArray?>? = null,
    val afterExtras: Array<ByteArray?>? = null,
    val uuidHigh: LongArray? = null,
    val uuidLow: LongArray? = null,
    val aux: Array<IntArray>? = null,
) {
    fun beforeExtrasAt(i: Int): ByteArray? = beforeExtras?.get(i)

    fun afterExtrasAt(i: Int): ByteArray? = afterExtras?.get(i)

    fun cellAt(i: Int): Cell = Cell(cell[i])

    fun isSorted(): Boolean {
        for (i in 1 until size) if (seq[i - 1] >= seq[i]) return false
        return true
    }

    fun pick(indexes: IntArray, n: Int = indexes.size): Rows {
        fun ints(a: IntArray) = IntArray(n) { a[indexes[it]] }
        return Rows(
            n, LongArray(n) { seq[indexes[it]] }, LongArray(n) { millis[indexes[it]] },
            ints(cell), ints(actor), ints(kind), ints(before), ints(after),
            beforeExtras?.let { e -> Array(n) { e[indexes[it]] } },
            afterExtras?.let { e -> Array(n) { e[indexes[it]] } },
            uuidHigh?.let { u -> LongArray(n) { u[indexes[it]] } },
            uuidLow?.let { u -> LongArray(n) { u[indexes[it]] } },
            aux?.let { cols -> Array(cols.size) { c -> IntArray(n) { cols[c][indexes[it]] } } },
        )
    }

    fun sorted(): Rows {
        if (isSorted()) return this
        val keys = LongArray(size) { seq[it] }
        val order = (0 until size).sortedBy { keys[it] }.toIntArray()
        return pick(order)
    }

    companion object {
        val EMPTY: Rows =
            Rows(0, LongArray(0), LongArray(0), IntArray(0), IntArray(0), IntArray(0), IntArray(0), IntArray(0))

        fun concat(parts: List<Rows>): Rows {
            if (parts.isEmpty()) return EMPTY
            if (parts.size == 1) return parts[0]
            val n = parts.sumOf { it.size }
            val extras = parts.any { it.beforeExtras != null || it.afterExtras != null }
            val uuids = parts.any { it.uuidHigh != null }
            val auxCols = parts.firstNotNullOfOrNull { it.aux }?.size ?: 0
            val out = Rows(
                n, LongArray(n), LongArray(n), IntArray(n), IntArray(n), IntArray(n), IntArray(n), IntArray(n),
                if (extras) arrayOfNulls(n) else null, if (extras) arrayOfNulls(n) else null,
                if (uuids) LongArray(n) else null, if (uuids) LongArray(n) else null,
                if (auxCols > 0) Array(auxCols) { IntArray(n) } else null,
            )
            var at = 0
            for (p in parts) {
                p.seq.copyInto(out.seq, at, 0, p.size)
                p.millis.copyInto(out.millis, at, 0, p.size)
                p.cell.copyInto(out.cell, at, 0, p.size)
                p.actor.copyInto(out.actor, at, 0, p.size)
                p.kind.copyInto(out.kind, at, 0, p.size)
                p.before.copyInto(out.before, at, 0, p.size)
                p.after.copyInto(out.after, at, 0, p.size)
                p.beforeExtras?.copyInto(out.beforeExtras ?: emptyArray(), at, 0, p.size)
                p.afterExtras?.copyInto(out.afterExtras ?: emptyArray(), at, 0, p.size)
                p.uuidHigh?.copyInto(out.uuidHigh ?: LongArray(0), at, 0, p.size)
                p.uuidLow?.copyInto(out.uuidLow ?: LongArray(0), at, 0, p.size)
                p.aux?.forEachIndexed { c, col -> col.copyInto(out.aux!![c], at, 0, p.size) }
                at += p.size
            }
            return out
        }
    }
}

/** Blobs compare by content, and an empty one is no blob at all. */
internal fun sameExtras(a: ByteArray?, b: ByteArray?): Boolean =
    if (a == null || a.isEmpty()) b == null || b.isEmpty() else b != null && a.contentEquals(b)
