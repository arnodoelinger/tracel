package com.tracel.storage.ports.world

import com.tracel.storage.StorageUnit
import com.tracel.storage.TracelStorage
import com.tracel.storage.codec.Tag
import com.tracel.storage.util.eachRow
import java.util.*
import com.tracel.engine.world.LiveEntities as LiveEntitiesPort

/** One empty row per entity. */
class LiveEntities(private val storage: TracelStorage) : LiveEntitiesPort {
    override fun loadAll(): Set<UUID> =
        StorageUnit.open(storage.engine).use { unit ->
            val out = HashSet<UUID>()
            unit.eachRow(Tag.LIVE_ENTITY.prefix) { cursor -> out += UUID(cursor.keyU64(1), cursor.keyU64(9)) }
            out
        }

    override suspend fun writeAll(changes: Map<UUID, Boolean>) {
        if (changes.isEmpty()) return
        storage.write {
            for ((uuid, live) in changes) {
                val key = Tag.LIVE_ENTITY[uuid]
                if (live) put(key, EMPTY) else delete(key)
            }
        }
    }

    private companion object {
        val EMPTY = ByteArray(0)
    }
}
