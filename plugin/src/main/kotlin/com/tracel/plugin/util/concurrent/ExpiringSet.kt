package com.tracel.plugin.util.concurrent

/**
 * A set of keys that expire after [ttlMillis].
 *
 * It's useful for short-lived de-duplication where only key presence matters.
 */
internal class ExpiringSet<K : Any>(ttlMillis: Long, capacity: Int = Int.MAX_VALUE) {
    private val entries = ExpiringMap<K, Unit>(ttlMillis, capacity)

    /** Adds [key] if absent. */
    fun add(key: K): Boolean = entries.putIfAbsent(key, Unit)

    /** Adds [key], or makes it fresh again when it is there. */
    fun mark(key: K) {
        entries.put(key, Unit)
    }

    /** @return whether [key] was present and non-expired. */
    fun remove(key: K): Boolean = entries.remove(key) != null

    /** @return whether [key] is present and non-expired. */
    operator fun contains(key: K): Boolean = key in entries
}
