package com.tracel.plugin.mode

import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent
import com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent
import com.github.benmanes.caffeine.cache.Caffeine
import com.tracel.model.world.entity.EntityTypeKey
import com.tracel.storage.intern.EntityKindSource
import org.bukkit.entity.Item
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * What every mob in the world is, for the storage thread to ask the first time it writes one down.
 *
 * Only a hand-off: the answer is stored with the entity, so this holds the living, plus the just-gone for a minute,
 * because the storage thread is a little behind the server.
 */
internal class EntityKinds : EntityKindSource, Listener {
    private val living = ConcurrentHashMap<UUID, EntityTypeKey>()
    private val gone = Caffeine.newBuilder().expireAfterWrite(Duration.ofMinutes(1)).maximumSize(GONE_KEPT).build<UUID, EntityTypeKey>()

    override fun kindOf(uuid: UUID): EntityTypeKey? = living[uuid] ?: gone.getIfPresent(uuid)

    @EventHandler(priority = EventPriority.MONITOR)
    fun onAdd(event: EntityAddToWorldEvent) {
        val entity = event.entity
        if (entity is Player || entity is Item) return
        living[entity.uniqueId] = EntityTypeKey(entity.type.key().asString())
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onRemove(event: EntityRemoveFromWorldEvent) {
        living.remove(event.entity.uniqueId)?.let { gone.put(event.entity.uniqueId, it) }
    }

    private companion object {
        const val GONE_KEPT = 50_000L
    }
}
