package com.tracel.plugin.mode

import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryOpenEvent
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentSkipListMap

/**
 * When each player had a container open. Everything a player moves while one is open is one visit, and lookup
 * shows the visit's result, not every click.
 */
internal object PlayerSessions {
    private const val OPEN = Long.MAX_VALUE
    private const val SLACK_MILLIS = 1_000L

    private val visits = ConcurrentHashMap<UUID, ConcurrentSkipListMap<Long, Long>>()
    private var file: File? = null

    /** Reads [store] and, from then on, appends to it; the players online now are marked as they are. */
    fun open(store: File) {
        file = store
        if (!store.isFile) return
        runCatching {
            store.forEachLine { line ->
                val (uuid, from, to) = line.split(' ').takeIf { it.size == 3 } ?: return@forEachLine
                visits.getOrPut(UUID.fromString(uuid)) { ConcurrentSkipListMap() }[from.toLong()] = to.toLong()
            }
        }
    }

    /** The start of the visit [player] was in at [millis], if any; it names the visit. */
    fun at(player: UUID, millis: Long): Long? {
        val visit = visits[player]?.floorEntry(millis) ?: return null
        return visit.key.takeIf { visit.value == OPEN || millis <= visit.value + SLACK_MILLIS }
    }

    private fun begin(player: UUID, millis: Long) {
        visits.getOrPut(player) { ConcurrentSkipListMap() }[millis] = OPEN
    }

    private fun end(player: UUID, millis: Long) {
        val own = visits[player] ?: return
        val last = own.lastEntry() ?: return
        if (last.value != OPEN) return
        own[last.key] = millis
        runCatching { synchronized(this) { file?.appendText("$player ${last.key} $millis\n") } }
    }

    class SessionListener : Listener {
        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        fun onOpen(event: InventoryOpenEvent) = begin(event.player.uniqueId, System.currentTimeMillis())

        @EventHandler(priority = EventPriority.MONITOR)
        fun onClose(event: InventoryCloseEvent) = end(event.player.uniqueId, System.currentTimeMillis())
    }
}
