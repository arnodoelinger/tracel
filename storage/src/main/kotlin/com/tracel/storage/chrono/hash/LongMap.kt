package com.tracel.storage.chrono.hash

/**
 * Long keys to values.
 *
 * Not thread-safe.
 */
class LongMap<V : Any>(expected: Int = 16) {
    private var mask = tableSize(expected) - 1
    private var keys = LongArray(mask + 1)

    @Suppress("UNCHECKED_CAST")
    private var values = arrayOfNulls<Any>(mask + 1) as Array<V?>

    var size: Int = 0; private set

    /** Get the value for [key], or `null` if none. */
    operator fun get(key: Long): V? {
        var at = mix(key) and mask
        while (true) {
            val v = values[at] ?: return null
            if (keys[at] == key) return v
            at = (at + 1) and mask
        }
    }

    /** Sets [key] to [value], and says what it was. */
    fun put(key: Long, value: V): V? {
        var at = mix(key) and mask
        while (true) {
            val v = values[at] ?: break
            if (keys[at] == key) {
                values[at] = value
                return v
            }
            at = (at + 1) and mask
        }
        keys[at] = key
        values[at] = value
        if (++size * 2 > mask + 1) grow()
        return null
    }

    /** Takes [key] out, and says what it was. */
    fun remove(key: Long): V? {
        var at = mix(key) and mask
        while (true) {
            val v = values[at] ?: return null
            if (keys[at] == key) {
                shiftBack(at)
                size--
                return v
            }
            at = (at + 1) and mask
        }
    }

    /** Every key and value, in no order. */
    inline fun forEach(action: (key: Long, value: V) -> Unit) {
        for (i in 0 until capacity()) {
            val v = valueAt(i) ?: continue
            action(keyAt(i), v)
        }
    }

    /** Every value, in no order. */
    fun values(): List<V> {
        val out = ArrayList<V>(size)
        for (v in values) if (v != null) out += v
        return out
    }

    /** Forget everything, so the next [put] starts at zero again. */
    fun clear() {
        values.fill(null)
        size = 0
    }

    @PublishedApi
    internal fun capacity(): Int = mask + 1

    @PublishedApi
    internal fun keyAt(slot: Int): Long = keys[slot]

    @PublishedApi
    internal fun valueAt(slot: Int): V? = values[slot]

    // Knuth's algorithm R
    private fun shiftBack(hole: Int) {
        var gap = hole
        var at = (hole + 1) and mask
        while (true) {
            values[at] ?: break
            val home = mix(keys[at]) and mask
            val stays = if (gap <= at) home in (gap + 1)..at else home in (gap + 1)..mask || home <= at
            if (!stays) {
                keys[gap] = keys[at]
                values[gap] = values[at]
                gap = at
            }
            at = (at + 1) and mask
        }
        values[gap] = null
    }

    private fun grow() {
        val oldKeys = keys
        val oldValues = values
        mask = (mask shl 1) or 1
        keys = LongArray(mask + 1)
        @Suppress("UNCHECKED_CAST")
        values = arrayOfNulls<Any>(mask + 1) as Array<V?>
        for (i in oldValues.indices) {
            val v = oldValues[i] ?: continue
            var at = mix(oldKeys[i]) and mask
            while (values[at] != null) at = (at + 1) and mask
            keys[at] = oldKeys[i]
            values[at] = v
        }
    }

    private companion object {
        fun tableSize(expected: Int): Int = Integer.highestOneBit(maxOf(expected, 4) * 2 - 1) shl 1
    }
}
