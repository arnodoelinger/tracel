package com.tracel.storage.chrono

/**
 * Rows, one at a time, into growing columns.
 *
 * Not thread-safe.
 */
class RowsBuilder(initial: Int = 64) {
    var size: Int = 0; private set

    private var seq = LongArray(initial)
    private var millis = LongArray(initial)
    private var cell = IntArray(initial)
    private var actor = IntArray(initial)
    private var kind = IntArray(initial)
    private var before = IntArray(initial)
    private var after = IntArray(initial)
    private var beforeExtras: Array<ByteArray?>? = null
    private var afterExtras: Array<ByteArray?>? = null
    private var uuidHigh: LongArray? = null
    private var uuidLow: LongArray? = null
    private var aux: Array<IntArray>? = null

    /** Adds one row. An empty blob is no blob. */
    fun add(
        seq: Long, millis: Long, cell: Int, actor: Int, kind: Int, before: Int, after: Int,
        beforeExtras: ByteArray? = null, afterExtras: ByteArray? = null,
    ) {
        val i = row(seq, millis, cell, actor, kind, before, after)
        extras(i, beforeExtras, afterExtras)
    }

    /** Adds one entity row. */
    fun addEntity(
        seq: Long, millis: Long, cell: Int, actor: Int, kind: Int, type: Int, high: Long, low: Long,
        beforeShape: ByteArray?, afterShape: ByteArray?,
    ) {
        val i = row(seq, millis, cell, actor, kind, type, type)
        extras(i, beforeShape, afterShape)
        longs(i, high, low)
    }

    /** Adds one flow of a transaction. */
    fun addFlow(
        seq: Long, millis: Long, cell: Int, causedBy: Int, kind: Int, item: Int, source: Int, destination: Int,
        flowIndex: Int, quantity: Long, txn: Long, lots: ByteArray?, home: Tile,
    ) {
        val i = row(seq, millis, cell, causedBy, kind, item, source)
        extras(i, lots, null)
        longs(i, quantity, txn)
        ints(i, destination, flowIndex, home.world, home.x, home.z)
    }

    /** Copies row [i] of [rows] in, whichever kind of row it is. */
    fun add(rows: Rows, i: Int) {
        val at =
            row(rows.seq[i], rows.millis[i], rows.cell[i], rows.actor[i], rows.kind[i], rows.before[i], rows.after[i])
        extras(at, rows.beforeExtrasAt(i), rows.afterExtrasAt(i))
        rows.uuidHigh?.let { longs(at, it[i], rows.uuidLow?.get(i) ?: 0L) }
        rows.aux?.let { cols -> ints(at, *IntArray(cols.size) { cols[it][i] }) }
    }

    /** Forgets every row from [n] on. */
    fun truncate(n: Int) {
        require(n in 0..size) { "cannot cut $size rows back to $n." }
        beforeExtras?.fill(null, n, size)
        afterExtras?.fill(null, n, size)
        size = n
    }

    /** Clear the builder. */
    fun clear(): Unit = truncate(0)

    /** The rows so far, sharing the builder's arrays: read them before adding more. */
    fun rows(): Rows =
        Rows(size, seq, millis, cell, actor, kind, before, after, beforeExtras, afterExtras, uuidHigh, uuidLow, aux)

    private fun row(seq: Long, millis: Long, cell: Int, actor: Int, kind: Int, before: Int, after: Int): Int {
        if (size == this.seq.size) grow(maxOf(size shl 1, 4))
        val i = size++
        this.seq[i] = seq
        this.millis[i] = millis
        this.cell[i] = cell
        this.actor[i] = actor
        this.kind[i] = kind
        this.before[i] = before
        this.after[i] = after
        beforeExtras?.set(i, null)
        afterExtras?.set(i, null)
        uuidHigh?.set(i, 0L)
        uuidLow?.set(i, 0L)
        aux?.forEach { it[i] = 0 }
        return i
    }

    private fun extras(i: Int, be: ByteArray?, ae: ByteArray?) {
        val b = be?.takeIf { it.isNotEmpty() }
        val a = ae?.takeIf { it.isNotEmpty() }
        if (b == null && a == null) return
        val bs = beforeExtras ?: arrayOfNulls<ByteArray>(seq.size).also { beforeExtras = it }
        val a2 = afterExtras ?: arrayOfNulls<ByteArray>(seq.size).also { afterExtras = it }
        bs[i] = b
        a2[i] = a
    }

    private fun longs(i: Int, high: Long, low: Long) {
        val h = uuidHigh ?: LongArray(seq.size).also { uuidHigh = it }
        val l = uuidLow ?: LongArray(seq.size).also { uuidLow = it }
        h[i] = high
        l[i] = low
    }

    private fun ints(i: Int, vararg values: Int) {
        val cols = aux ?: Array(values.size) { IntArray(seq.size) }.also { aux = it }
        for (c in values.indices) cols[c][i] = values[c]
    }

    private fun grow(capacity: Int) {
        seq = seq.copyOf(capacity); millis = millis.copyOf(capacity); cell = cell.copyOf(capacity)
        actor = actor.copyOf(capacity); kind = kind.copyOf(capacity); before = before.copyOf(capacity)
        after = after.copyOf(capacity)
        beforeExtras = beforeExtras?.copyOf(capacity); afterExtras = afterExtras?.copyOf(capacity)
        uuidHigh = uuidHigh?.copyOf(capacity); uuidLow = uuidLow?.copyOf(capacity)
        aux = aux?.let { cols -> Array(cols.size) { cols[it].copyOf(capacity) } }
    }
}
