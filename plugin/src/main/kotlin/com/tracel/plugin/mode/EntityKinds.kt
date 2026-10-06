package com.tracel.plugin.mode

import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent
import com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent
import com.github.benmanes.caffeine.cache.Caffeine
import com.tracel.annotations.Observes
import com.tracel.model.world.entity.EntityTypeKey
import com.tracel.engine.actor.EntityKindSource
import org.bukkit.entity.Item
import org.bukkit.entity.Player
import org.bukkit.event.Listener
import java.time.Duration
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/** What every mob in the world is, for the storage thread to ask the first time it writes one down. */
internal class EntityKinds : EntityKindSource, Listener {
    private val living = ConcurrentHashMap<UUID, EntityTypeKey>()
    private val gone = Caffeine.newBuilder().expireAfterWrite(Duration.ofMinutes(1)).maximumSize(GONE_KEPT)
        .build<UUID, EntityTypeKey>()

    private companion object {
        const val GONE_KEPT = 50_000L
    }

    override fun kindOf(uuid: UUID): EntityTypeKey? = living[uuid] ?: gone.getIfPresent(uuid)

    @Observes(ignoreCancelled = false)
    fun onAdd(event: EntityAddToWorldEvent) {
        val entity = event.entity
        if (entity is Player || entity is Item) return
        living[entity.uniqueId] = EntityTypeKey(entity.type.key().asString())
    }

    @Observes(ignoreCancelled = false)
    fun onRemove(event: EntityRemoveFromWorldEvent) {
        living.remove(event.entity.uniqueId)?.let { gone.put(event.entity.uniqueId, it) }
    }
}
