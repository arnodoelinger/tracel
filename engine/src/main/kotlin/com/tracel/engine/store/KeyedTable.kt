package com.tracel.engine.store

/** A durable map: one row per key. */
public interface KeyedTable<K, V> {
    /** Writes every entry in one batch; a `null` value deletes its row. */
    public suspend fun putAll(entries: Map<K, V?>)

    /** The value under [key], or `null`. */
    public suspend fun get(key: K): V?

    /** Every row. Blocks the caller: for startup, before anything else writes. */
    public fun loadAll(): Map<K, V>
}
