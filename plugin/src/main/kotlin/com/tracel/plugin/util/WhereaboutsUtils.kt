package com.tracel.plugin.util

import com.tracel.model.holder.HolderId
import com.tracel.model.id.WorldId
import com.tracel.storage.ports.world.GroundPositions
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import org.bukkit.entity.Entity

/** Last chunk a tracked entity was seen in. */
class EntityWhereabouts(private val capacity: Int = UNBOUNDED) {
    private val at = ConcurrentHashMap<UUID, HolderId.Block>()
    private val order = if (capacity > 0) ConcurrentLinkedQueue<UUID>() else null

    private companion object {
        const val UNBOUNDED = 0
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

    /** Stop tracking an entity. */
    fun forget(uuid: UUID) {
        at.remove(uuid)
    }

    /** @return the entity's last known position. */
    fun at(uuid: UUID): HolderId.Block? = at[uuid]
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
