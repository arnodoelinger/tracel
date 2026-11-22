package com.tracel.storage.chrono

/** The order the edits of one batch get their sequence in: tile by tile, then cell by cell. */
internal object PlaceOrder {
    /** Indexes `0 until n`, by the block at `x(i)`, `y(i)`, `z(i)`. */
    inline fun of(n: Int, x: (Int) -> Int, y: (Int) -> Int, z: (Int) -> Int): IntArray {
        val tile = LongArray(n)
        val cell = IntArray(n)
        var sorted = true
        for (i in 0 until n) {
            val xi = x(i)
            val zi = z(i)
            tile[i] = ((xi shr Tile.SHIFT).toLong() shl 32) or ((zi shr Tile.SHIFT).toLong() and 0xFFFF_FFFFL)
            cell[i] = Cell.of(xi, y(i), zi).packed
            if (i > 0 && (tile[i] < tile[i - 1] || tile[i] == tile[i - 1] && cell[i] < cell[i - 1])) sorted = false
        }
        if (sorted) return IntArray(n) { it }
        return (0 until n).sortedWith { a, b ->
            val t = tile[a].compareTo(tile[b])
            if (t != 0) t else cell[a].compareTo(cell[b])
        }.toIntArray()
    }
}
