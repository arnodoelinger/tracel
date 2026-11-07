package com.tracel.storage.table

import com.tracel.engine.store.KeyedTable
import com.tracel.storage.StorageUnit
import com.tracel.storage.TracelStorage
import com.tracel.storage.codec.Tag
import com.tracel.storage.util.eachRow

/** A durable map: one row per key. */
open class StoredKeyedTable<K, V>(
    storage: TracelStorage,
    tag: Tag,
    keys: KeyCodec<K>,
    private val values: ValueCodec<V>,
    private val keepFirst: Boolean = false,
) : AbstractTable<K>(storage, tag, keys), KeyedTable<K, V> {
    /** Writes every entry in one batch; a `null` value deletes its row. */
    override suspend fun putAll(entries: Map<K, V?>) {
        if (entries.isEmpty()) return
        storage.write {
            for ((key, value) in entries) {
                if (value == null) {
                    delete(foundKey(key) ?: continue)
                } else {
                    val row = keyOf(key)
                    if (keepFirst && get(row) != null) continue
                    put(row, values.write(storage, this, value))
                }
            }
        }
    }

    /** The value under [key], or `null`. */
    override suspend fun get(key: K): V? = storage.read {
        val row = foundKey(key) ?: return@read null
        get(row)?.let { values.read(storage, this, it) }
    }

    /** Every row. Blocks the caller: for startup, before anything else writes. */
    override fun loadAll(): Map<K, V> = StorageUnit.open(storage.engine).use { unit ->
        val out = HashMap<K, V>()
        unit.eachRow(tag.prefix) { cursor ->
            val key = keys.read(storage, unit, tag, cursor)
            out[key] = values.read(storage, unit, cursor.value()) ?: return@eachRow
        }
        out
    }
}
