package com.tracel.storage.table

import com.tracel.storage.TracelStorage
import com.tracel.storage.codec.Tag
import com.tracel.storage.util.eachRow

/**
 * A durable queue per owner: rows under the owner and an id that only grows,
 * so they read back in the order kept, and a claim takes them all at once.
 */
open class ClaimQueue<O, V>(
    storage: TracelStorage,
    tag: Tag,
    owners: KeyCodec<O>,
    private val values: ValueCodec<V>,
    private val nextId: suspend () -> Long,
) : AbstractTable<O>(storage, tag, owners) {
    /** Queues [items] for [owner] in one batch. */
    suspend fun addAll(owner: O, items: List<V>) {
        if (items.isEmpty()) return
        val ids = LongArray(items.size)
        for (i in ids.indices) ids[i] = nextId()
        storage.write {
            val parts = keys.intern(storage, this, owner)
            for (i in items.indices) put(tag.get(*parts, ids[i]), values.write(storage, this, items[i]))
        }
    }

    /** Reads and deletes everything queued for [owner], oldest first, each with the id it was queued under. */
    suspend fun claim(owner: O): List<Pair<Long, V>> = storage.write {
        val parts = keys.find(storage, this, owner) ?: return@write emptyList()
        val out = ArrayList<Pair<Long, V>>()
        val rows = ArrayList<ByteArray>()
        eachRow(tag.get(*parts)) { cursor ->
            val value = values.read(storage, this, cursor.value())
            if (value != null) out += tag.long(cursor, parts.size) to value
            rows += cursor.key()
        }
        rows.forEach(::delete)
        out
    }
}
