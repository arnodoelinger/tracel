package com.tracel.plugin.util

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.collections.iterator

/**
 * A concurrent map whose entries expire after [ttlMillis].
 *
 * Expired entries are never returned. Reads remove stale entries, while writes periodically
 * sweep the map to reclaim entries that are no longer accessed.
 */
internal class ExpiringMap<K : Any, V : Any>(
    private val ttlMillis: Long,
    private val capacity: Int = Int.MAX_VALUE,
) {
    private class Entry<V>(val value: V, val at: Long)

    private val entries = ConcurrentHashMap<K, Entry<V>>()
    private val sweptAt = AtomicLong(System.currentTimeMillis())

    val size: Int get() = entries.size

    /**
     * Stores [value] under [key].
     *
     * @return the value or `false` when a new key could not be stored.
     */
    fun put(key: K, value: V, now: Long = System.currentTimeMillis()): Boolean {
        val stored = store(key, value, now)
        sweep(now)
        return stored
    }

    /** Stores [value] under every key in [keys] using the same timestamp. */
    fun putAll(keys: Iterable<K>, value: V, now: Long = System.currentTimeMillis()) {
        for (key in keys) store(key, value, now)
        sweep(now)
    }

    /**
     * Stores [value] only when [key] is absent or expired.
     *
     * @return `true` when the value was stored.
     */
    fun putIfAbsent(key: K, value: V, now: Long = System.currentTimeMillis()): Boolean {
        // Approximate because we need to avoid serializing concurrent writers
        val room = entries.size < capacity
        var stored = false
        entries.compute(key) { _, existing ->
            stored = false
            when {
                existing != null && now - existing.at <= ttlMillis -> existing
                existing == null && !room -> null
                else -> Entry(value, now).also { stored = true }
            }
        }
        sweep(now)
        return stored
    }

    /** @return the value for [key], or `null` when it is absent or expired. */
    operator fun get(key: K): V? {
        val entry = entries[key] ?: return null
        if (System.currentTimeMillis() - entry.at > ttlMillis) {
            entries.remove(key, entry)
            return null
        }
        return entry.value
    }

    /**
     * Forgets everything.
     *
     * For tests and for a reload.
     */
    fun clear() {
        entries.clear()
    }

    /** @return the value for [key], or `null` when it is absent or expired. */
    fun remove(key: K): V? {
        val entry = entries.remove(key) ?: return null
        return entry.value.takeIf { System.currentTimeMillis() - entry.at <= ttlMillis }
    }

    /** @return whether [key] has a non-expired value. */
    operator fun contains(key: K): Boolean = get(key) != null

    /** Calls [action] for every non-expired entry. */
    fun forEachFresh(action: (K, V) -> Unit) {
        val now = System.currentTimeMillis()
        for ((key, entry) in entries) {
            if (now - entry.at > ttlMillis) continue
            action(key, entry.value)
        }
    }

    private fun store(key: K, value: V, now: Long): Boolean {
        val room = entries.size < capacity || entries.containsKey(key)
        if (room) entries[key] = Entry(value, now)
        return room
    }

    private fun sweep(now: Long) {
        val previous = sweptAt.get()
        if (now - previous < ttlMillis) return
        if (!sweptAt.compareAndSet(previous, now)) return
        entries.values.removeIf { now - it.at > ttlMillis }
    }
}

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
