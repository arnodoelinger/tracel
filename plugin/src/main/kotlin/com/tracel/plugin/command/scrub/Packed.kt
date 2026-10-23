package com.tracel.plugin.command.scrub

/** A block position in one `Long`, and a chunk and the spot in it in an `Int`. */
internal object Packed {
    private const val BITS = 0x3FFFFFFL
    private const val FLOOR = 2048

    fun of(x: Int, y: Int, z: Int): Long =
        ((x.toLong() and BITS) shl 38) or ((z.toLong() and BITS) shl 12) or ((y + FLOOR).toLong() and 0xFFF)

    fun x(pos: Long): Int = ((pos shr 38).toInt() shl 6) shr 6

    fun z(pos: Long): Int = (((pos shr 12) and BITS).toInt() shl 6) shr 6

    fun y(pos: Long): Int = (pos and 0xFFF).toInt() - FLOOR

    fun chunkOf(pos: Long): Long = chunk(x(pos) shr 4, z(pos) shr 4)

    fun chunk(chunkX: Int, chunkZ: Int): Long = (chunkX.toLong() shl 32) or (chunkZ.toLong() and 0xFFFFFFFFL)

    fun chunkX(chunk: Long): Int = (chunk shr 32).toInt()

    fun chunkZ(chunk: Long): Int = chunk.toInt()

    fun local(pos: Long): Int = ((y(pos) + FLOOR) shl 8) or ((x(pos) and 15) shl 4) or (z(pos) and 15)

    fun localX(chunk: Long, local: Int): Int = (chunkX(chunk) shl 4) + ((local shr 4) and 15)

    fun localY(local: Int): Int = (local shr 8) - FLOOR

    fun localZ(chunk: Long, local: Int): Int = (chunkZ(chunk) shl 4) + (local and 15)
}

/** `Int` keys, to `Int` values, without a box in sight. */
internal class IntIntMap {
    private var keys = IntArray(INITIAL) { EMPTY }
    private var values = IntArray(INITIAL)

    var size = 0
        private set

    fun put(key: Int, value: Int) {
        if ((size + 1) * 2 > keys.size) grow()
        var at = slot(key, keys.size)
        while (keys[at] != EMPTY && keys[at] != key) at = (at + 1) and (keys.size - 1)
        if (keys[at] == EMPTY) {
            keys[at] = key
            size++
        }
        values[at] = value
    }

    fun forEach(visit: (Int, Int) -> Unit) {
        for (i in keys.indices) if (keys[i] != EMPTY) visit(keys[i], values[i])
    }

    private fun grow() {
        val old = keys
        val oldValues = values
        keys = IntArray(old.size * 2) { EMPTY }
        values = IntArray(old.size * 2)
        size = 0
        for (i in old.indices) if (old[i] != EMPTY) put(old[i], oldValues[i])
    }

    private fun slot(key: Int, length: Int): Int = ((key * -1640531535) ushr 7) and (length - 1)

    private companion object {
        const val EMPTY = -1
        const val INITIAL = 16
    }
}

/** A set of `Long`s, the packed positions of [Packed], without a box in sight. */
internal class LongSet(expected: Int = INITIAL) {
    private var keys = LongArray(capacity(expected)) { EMPTY }

    var size = 0; private set

    /** Adds [key]; `false` when it was there already. */
    fun add(key: Long): Boolean {
        if ((size + 1) * 2 > keys.size) grow()
        var at = slot(key, keys.size)
        while (keys[at] != EMPTY) {
            if (keys[at] == key) return false
            at = (at + 1) and (keys.size - 1)
        }
        keys[at] = key
        size++
        return true
    }

    operator fun contains(key: Long): Boolean {
        var at = slot(key, keys.size)
        while (keys[at] != EMPTY) {
            if (keys[at] == key) return true
            at = (at + 1) and (keys.size - 1)
        }
        return false
    }

    inline fun forEach(visit: (Long) -> Unit) {
        for (key in table()) if (key != EMPTY) visit(key)
    }

    @PublishedApi
    internal fun table(): LongArray = keys

    private fun grow() {
        val old = keys
        keys = LongArray(old.size * 2) { EMPTY }
        size = 0
        for (key in old) if (key != EMPTY) add(key)
    }

    private fun slot(key: Long, length: Int): Int {
        val mixed = key * -7046029254386353131L
        return (mixed xor (mixed ushr 32)).toInt() and (length - 1)
    }

    companion object {
        const val EMPTY = Long.MIN_VALUE
        private const val INITIAL = 16

        private fun capacity(expected: Int): Int {
            var size = INITIAL
            while (size < expected * 2) size = size shl 1
            return size
        }
    }
}
