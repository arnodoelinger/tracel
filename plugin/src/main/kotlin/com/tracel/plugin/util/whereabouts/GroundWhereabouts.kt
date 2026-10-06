package com.tracel.plugin.util.whereabouts

import com.tracel.engine.world.GroundPositions
import com.tracel.model.holder.HolderId
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/** Where ground items were last standing. */
class GroundWhereabouts(private val store: GroundPositions) {
    private val recent = EntityWhereabouts(capacity = KEPT_IN_MEMORY)
    private val pending = ConcurrentHashMap<HolderId.ItemEntity, HolderId.Block>()

    private companion object {
        const val KEPT_IN_MEMORY = 50_000
    }

    /** Remember a position directly. */
    fun remember(uuid: UUID, at: HolderId.Block) {
        recent.remember(uuid, at)
        pending[HolderId.ItemEntity(uuid)] = at
    }

    /** @return where [uuid] last was, or `null` if nothing ever saw it. */
    suspend fun at(uuid: UUID): HolderId.Block? {
        recent.at(uuid)?.let { return it }
        pending[HolderId.ItemEntity(uuid)]?.let { return it }
        return store.find(HolderId.ItemEntity(uuid))
    }

    /** Commits everything queued. */
    suspend fun flush() {
        if (pending.isEmpty()) return
        val batch = HashMap<HolderId.ItemEntity, HolderId.Block>(pending.size)
        for (item in pending.keys.toList()) {
            pending.remove(item)?.let { batch[item] = it }
        }
        store.rememberAll(batch)
    }
}
