package com.tracel.storage.table

import com.tracel.engine.store.KeySet
import com.tracel.storage.StorageUnit
import com.tracel.storage.TracelStorage
import com.tracel.storage.codec.Tag
import com.tracel.storage.util.eachRow

/** A durable set: one empty row per member. */
open class StoredKeySet<K>(storage: TracelStorage, tag: Tag, keys: KeyCodec<K>) : AbstractTable<K>(storage, tag, keys),
    KeySet<K> {
    /** Adds the keys mapped to `true` and drops those mapped to `false`, in one batch. */
    override suspend fun putAll(changes: Map<K, Boolean>) {
        if (changes.isEmpty()) return
        storage.write {
            for ((key, member) in changes) {
                if (member) put(keyOf(key), EMPTY) else delete(foundKey(key) ?: continue)
            }
        }
    }

    /**
     * Every member.
     *
     * Blocks the caller: for startup, before anything else writes.
     */
    override fun loadAll(): Set<K> = StorageUnit.open(storage.engine).use { unit ->
        val out = HashSet<K>()
        unit.eachRow(tag.prefix) { cursor -> out += keys.read(storage, unit, tag, cursor) }
        out
    }

    private companion object {
        val EMPTY = ByteArray(0)
    }
}
