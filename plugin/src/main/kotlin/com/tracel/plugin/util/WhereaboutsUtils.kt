package com.tracel.plugin.util

import com.tracel.model.holder.HolderId
import com.tracel.model.world.WorldId
import com.tracel.storage.ports.world.GroundPositions
import org.bukkit.entity.Entity
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

    /** Remember the entity's current block position. */
    fun remember(entity: Entity) {
        val loc = entity.location
        val world = loc.world ?: return
        remember(entity.uniqueId, HolderId.Block(WorldId(world.uid), loc.blockX, loc.blockY, loc.blockZ))
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

/** Where ground items were last standing. */
class GroundWhereabouts(private val store: GroundPositions) {
    private val recent = EntityWhereabouts(capacity = KEPT_IN_MEMORY)
    private val pending = ConcurrentHashMap<HolderId.ItemEntity, HolderId.Block>()

    private companion object {
        const val KEPT_IN_MEMORY = 50_000
    }

    /**
     * Remember where [entity] is standing.
     *
     * Region thread and no storage hop.
     */
    fun remember(entity: Entity) {
        val loc = entity.location
        val world = loc.world ?: return
        remember(entity.uniqueId, HolderId.Block(WorldId(world.uid), loc.blockX, loc.blockY, loc.blockZ))
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
