package com.tracel.plugin.util.concurrent

import java.util.concurrent.ConcurrentHashMap

/** A small map whose truth is on disk and whose reads never wait for it. */
internal class DiskMirror<K : Any, V : Any> {
    private val live = ConcurrentHashMap<K, V>()
    private val dirty = ConcurrentHashMap<K, Any>()

    @Volatile
    private var write: (suspend (Map<K, V?>) -> Unit)? = null

    /** Starts over from [loaded], what disk holds, writing changes with [write] from now on. */
    fun open(loaded: Map<K, V>, write: suspend (Map<K, V?>) -> Unit) {
        live.clear()
        dirty.clear()
        live.putAll(loaded)
        this.write = write
    }

    /** @return the value [key] held, or `null` if nothing. */
    operator fun get(key: K): V? = live[key]

    /** @return whether [key] is known to hold a value. */
    operator fun contains(key: K): Boolean = live.containsKey(key)

    /** A copy, as it is now. */
    fun keys(): Set<K> = HashSet(live.keys)

    /**
     * Sets [key] to [value], or removes it if [value] is `null`.
     *
     * The change will be written to disk on the next flush.
     */
    fun put(key: K, value: V) {
        live.compute(key) { _, _ ->
            dirty[key] = value
            value
        }
    }

    /** @return what [key] held, or null if nothing. */
    fun remove(key: K): V? {
        var was: V? = null
        live.compute(key) { _, old ->
            was = old
            if (old != null) dirty[key] = GONE
            null
        }
        return was
    }

    /**
     * Forgets everything, here and in what was waiting for disk.
     *
     * For after disk was wiped under it.
     */
    fun forgetAll() {
        live.clear()
        dirty.clear()
    }

    /**
     * Writes every change since the last flush.
     *
     * A failed write is kept for the next one, unless newer came since.
     */
    @Suppress("UNCHECKED_CAST")
    suspend fun flush() {
        val write = write ?: return
        if (dirty.isEmpty()) return
        val batch = HashMap<K, V?>(dirty.size)
        for (key in dirty.keys.toList()) {
            val change = dirty.remove(key) ?: continue
            batch[key] = if (change === GONE) null else change as V
        }
        try {
            write(batch)
        } catch (failure: Throwable) {
            for ((key, value) in batch) dirty.putIfAbsent(key, value ?: GONE)
            throw failure
        }
    }

    private object GONE
}
