package com.tracel.plugin.command.presenter

import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent
import org.bukkit.entity.Item
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import java.util.UUID

// TODO: TEMPORARY!!

/**
 * What a mob was, for the log only remembers who it was: a UUID. Filled while the mob is alive and from the
 * entity records of a search. Gone with a restart.
 */
internal object EntityKindPresenter {
    private const val CAPACITY = 20_000

    private val kinds = object : LinkedHashMap<UUID, String>(1024, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<UUID, String>) = size > CAPACITY
    }

    /** Remembers the type of an entity. */
    fun remember(uuid: UUID, type: String) = synchronized(kinds) { kinds[uuid] = type; }

    /** Recalls the type of an entity, if it was remembered. */
    fun of(uuid: UUID): String? = synchronized(kinds) { kinds[uuid] }

    class EntityListener : Listener {
        @EventHandler
        fun onAdd(event: EntityAddToWorldEvent) {
            val entity = event.entity
            if (entity is Player || entity is Item) return
            remember(entity.uniqueId, entity.type.key().asString())
        }
    }
}
