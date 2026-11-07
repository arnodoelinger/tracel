package com.tracel.engine.store

/** A durable set. */
public interface KeySet<K> {
    /** Adds the keys mapped to `true` and drops those mapped to `false`, in one batch. */
    public suspend fun putAll(changes: Map<K, Boolean>)

    /**
     * Every member.
     *
     * Blocks the caller: for startup, before anything else writes.
     */
    public fun loadAll(): Set<K>
}
