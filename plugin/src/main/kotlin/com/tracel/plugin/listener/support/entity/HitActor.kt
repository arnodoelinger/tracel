package com.tracel.plugin.listener.support.entity

import com.tracel.annotations.Unstable
import com.tracel.model.holder.HolderId
import com.tracel.plugin.util.concurrent.ExpiringMap
import java.util.*
import org.bukkit.NamespacedKey
import org.bukkit.entity.Entity
import org.bukkit.persistence.PersistentDataType

/**
 * Who last struck something that goes off, breaks or drops in one hit and names nobody itself: an end crystal,
 * a TNT minecart, an armor stand, a framed item.
 */
@Unstable
internal object HitActor {
    private const val TTL_MS = 10_000L

    private val byEntity = ExpiringMap<UUID, HolderId>(TTL_MS, 16_384)

    private val shot = ExpiringMap<UUID, Boolean>(TTL_MS, 16_384)

    fun hit(entity: Entity, by: HolderId, projectile: Boolean = false) {
        byEntity.put(entity.uniqueId, by)
        if (projectile) shot.put(entity.uniqueId, true) else shot.remove(entity.uniqueId)
    }

    fun wasShot(entity: UUID): Boolean = shot[entity] == true

    fun of(entity: Entity): HolderId? = of(entity.uniqueId)

    fun of(entity: UUID): HolderId? = byEntity[entity]

    /** The player a built wither or its skull goes back to, from the mark its builder left on it. */
    fun builderOf(entity: Entity): HolderId? = runCatching {
        val key = NamespacedKey.fromString("tracel:made_by") ?: return@runCatching null
        entity.persistentDataContainer.get(key, PersistentDataType.STRING)?.let { HolderId.Player(UUID.fromString(it)) }
    }.getOrNull()
}
