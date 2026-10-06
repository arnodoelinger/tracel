package com.tracel.plugin.util.concurrent

/**
 * A set of keys that expire after [ttlMillis].
 *
 * It's useful for short-lived de-duplication where only key presence matters.
 */
internal class ExpiringSet<K : Any>(ttlMillis: Long) {
    private val entries = ExpiringMap<K, Unit>(ttlMillis)

    /** Adds [key] if absent. */
    fun add(key: K): Boolean = entries.putIfAbsent(key, Unit)

    /** @return whether [key] is present and non-expired. */
    operator fun contains(key: K): Boolean = key in entries
}
