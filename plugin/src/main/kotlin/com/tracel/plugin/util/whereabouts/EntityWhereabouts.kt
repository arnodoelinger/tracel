package com.tracel.plugin.util.whereabouts

import com.tracel.model.holder.HolderId
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/** Last chunk a tracked entity was seen in. */
class EntityWhereabouts(private val capacity: Int = UNBOUNDED) {
    private val at = ConcurrentHashMap<UUID, HolderId.Block>()
    private val order = if (capacity > 0) ConcurrentLinkedQueue<UUID>() else null
    private val last = ConcurrentHashMap<UUID, HolderId.Block>()

    private companion object {
        const val UNBOUNDED = 0
        const val LAST_KEPT = 50_000
    }

    /** Remember an entity's position. */
    fun remember(uuid: UUID, pos: HolderId.Block) {
        val known = at.put(uuid, pos) != null
        val order = order ?: return
        if (known) return
        order += uuid
        while (at.size > capacity || order.size > capacity * 2) {
            at.remove(order.poll() ?: break)
        }
    }

    /** Stop tracking an entity; where it stood is still on hand through [lastKnown]. */
    fun forget(uuid: UUID) {
        val was = at.remove(uuid) ?: return
        if (last.size >= LAST_KEPT) last.clear()
        last[uuid] = was
    }

    /** @return the entity's last known position. */
    fun at(uuid: UUID): HolderId.Block? = at[uuid]

    /** @return where the entity stood, gone or not: what a vanished hull was owed lands there. */
    fun lastKnown(uuid: UUID): HolderId.Block? = at[uuid] ?: last[uuid]
}
