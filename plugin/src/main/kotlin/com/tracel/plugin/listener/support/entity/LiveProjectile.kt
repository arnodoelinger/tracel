package com.tracel.plugin.listener.support.entity

import com.tracel.engine.world.LiveEntities
import com.tracel.model.holder.HolderId
import com.tracel.plugin.util.concurrent.DiskMirror
import java.util.*

/** Booked projectiles still in the world. */
internal object LiveProjectile {
    private val uuids = DiskMirror<UUID, Unit>()

    /** Starts from what [store] holds. Before any listener runs. */
    fun open(store: LiveEntities) {
        uuids.open(store.loadAll().associateWith { }) { changes ->
            store.putAll(changes.mapValues { (_, live) -> live != null })
        }
    }

    /** @return the projectile's holder, if it is still in the world. */
    fun add(uuid: UUID) = uuids.put(uuid, Unit)

    /** Remove a projectile that is no longer in the world. */
    fun remove(uuid: UUID): Boolean = uuids.remove(uuid) != null

    /** @return whether the projectile is still in the world. */
    operator fun contains(uuid: UUID): Boolean = uuid in uuids

    /** @return the holders of all projectiles still in the world. */
    fun holders(): Set<HolderId> = uuids.keys().mapTo(HashSet()) { HolderId.PlacedEntity(it) }

    suspend fun flush() = uuids.flush()

    /** Forget all projectiles. */
    fun forgetAll() = uuids.forgetAll()
}
