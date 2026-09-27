package com.tracel.plugin.listener.support

import com.tracel.model.holder.HolderId
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Booked projectiles still in the world. */
internal object LiveProjectiles {
    private val uuids = ConcurrentHashMap.newKeySet<UUID>()

    fun add(uuid: UUID) {
        uuids += uuid
    }

    fun remove(uuid: UUID): Boolean = uuids.remove(uuid)

    operator fun contains(uuid: UUID): Boolean = uuid in uuids

    fun holders(): Set<HolderId> = uuids.mapTo(HashSet()) { HolderId.PlacedEntity(it) }
}
