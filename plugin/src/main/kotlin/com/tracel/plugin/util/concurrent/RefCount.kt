package com.tracel.plugin.util.concurrent

import java.util.concurrent.ConcurrentHashMap

/** How many holds each key has. A key is in the set while at least one of them is left. */
internal class RefCount<K : Any> {
    private val counts = ConcurrentHashMap<K, Int>()

    /** Whether [key] has a hold left. */
    operator fun contains(key: K): Boolean = counts.containsKey(key)

    /** Adds a hold on [key]. */
    fun hold(key: K) {
        counts.merge(key, 1, Int::plus)
    }

    /** Lifts one hold from [key]; the last one takes it out of the set. */
    fun release(key: K) {
        counts.computeIfPresent(key) { _, held -> if (held <= 1) null else held - 1 }
    }

    /** Takes [key] out whatever holds it has. */
    fun clear(key: K) {
        counts.remove(key)
    }
}
