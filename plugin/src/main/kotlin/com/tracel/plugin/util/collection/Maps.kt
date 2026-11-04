package com.tracel.plugin.util.collection

/**
 * Puts [value] under [key]; a map that already holds [limit] entries is emptied first:
 * a cache forgets, it never refuses.
 */
internal fun <K, V> MutableMap<K, V>.putOrReset(limit: Int, key: K, value: V) {
    if (size >= limit) clear()
    this[key] = value
}
