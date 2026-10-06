package com.tracel.plugin.capture.reads

import com.tracel.model.cause.CauseKind
import com.tracel.model.holder.HolderId
import com.tracel.plugin.adapter.item.toHolderId
import com.tracel.plugin.adapter.item.toItemTotals
import com.tracel.plugin.capture.DEATH_READ_QUIET_MILLIS
import com.tracel.plugin.capture.MaterialCapture
import com.tracel.plugin.listener.support.flow.isLedgeredHolder
import com.tracel.plugin.services.TracelServices
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

private const val CRAFT_OWED_MILLIS = 1_000L

/**
 * The inventory reads players have coming: at most one per player per tick, however many clicks asked for it.
 *
 * A read waits while a craft is still owed, and is dropped for a player who just died.
 */
internal class InventoryReads(private val services: TracelServices, private val capture: MaterialCapture) {
    private val inventoryQueued = ConcurrentHashMap.newKeySet<UUID>()
    private val pendingInventories = ConcurrentHashMap<UUID, ConcurrentLinkedQueue<Inventory>>()
    private val pendingReconcileCause = ConcurrentHashMap<UUID, CauseKind>()
    private val pendingRebaseline = ConcurrentHashMap.newKeySet<UUID>()
    private val diedAt = ConcurrentHashMap<UUID, Long>()
    private val craftsOwed = ConcurrentHashMap<UUID, Long>()

    /** One inventory read per player per tick. */
    fun scheduleReconcile(
        player: Player,
        inventories: Collection<Inventory> = listOf(player.inventory),
        cause: CauseKind = CauseKind.PLAYER_ACTION,
    ) {
        pendingInventories.getOrPut(player.uniqueId) { ConcurrentLinkedQueue() }.addAll(inventories)
        pendingReconcileCause[player.uniqueId] = cause
        armInventoryRead(player)
    }

    /** Updates the timestamp for when a craft is owed to a specific [player]. */
    fun craftOwed(player: UUID) {
        craftsOwed[player] = System.currentTimeMillis()
    }

    /** Removes the specified [player] from the list of players who are owed crafts. */
    fun craftBooked(player: UUID) {
        craftsOwed.remove(player)
    }

    /** [player] just died: the reads their death queued would book the dropped pockets as burned. */
    fun died(player: UUID) {
        diedAt[player] = System.currentTimeMillis()
        pendingInventories.remove(player)
    }

    /** [player] is back from death: empty pockets until the queued delivery lands, a read now burns what it owes. */
    fun respawned(player: UUID) = died(player)

    /** Whether a queued click read will diff [holder]: it accounts for what the click moved in or out of it. */
    fun reconcilePending(holder: HolderId): Boolean =
        pendingInventories.values.any { queue -> queue.any { it.toHolderId() == holder } }

    /** Snapshot without booking a transaction. */
    fun scheduleRebaseline(player: Player) {
        pendingRebaseline.add(player.uniqueId)
        armInventoryRead(player)
    }

    private fun craftOwedNow(player: UUID): Boolean {
        val since = craftsOwed[player] ?: return false
        if (System.currentTimeMillis() - since < CRAFT_OWED_MILLIS) return true
        craftsOwed.remove(player, since)
        return false
    }

    private fun armInventoryRead(player: Player) {
        if (!inventoryQueued.add(player.uniqueId)) return
        val ticket = services.pendingCaptures.owed()
        val scheduled = player.scheduler.runDelayed(services.plugin, {
            try {
                flushInventoryRead(player)
            } finally {
                services.pendingCaptures.done(ticket)
            }
        }, {
            inventoryQueued.remove(player.uniqueId)
            services.pendingCaptures.done(ticket)
        }, 1L)
        if (scheduled == null) {
            inventoryQueued.remove(player.uniqueId)
            services.pendingCaptures.done(ticket)
        }
    }

    private fun flushInventoryRead(player: Player) {
        val id = player.uniqueId
        inventoryQueued.remove(id)
        if (craftOwedNow(id)) {
            armInventoryRead(player)
            return
        }
        if (pendingRebaseline.remove(id)) {
            if (!player.isLedgeredHolder()) {
                capture.forget(HolderId.Player(id))
            } else {
                capture.rebaseline(player)
                rebaselineOpenContainer(player)
            }
        }
        val inventories = pendingInventories.remove(id)?.distinct().orEmpty()
        val cause = pendingReconcileCause.remove(id) ?: CauseKind.PLAYER_ACTION
        if (player.isDead || System.currentTimeMillis() - (diedAt[id] ?: 0L) < DEATH_READ_QUIET_MILLIS) return
        if (inventories.isNotEmpty() && player.isLedgeredHolder()) {
            capture.reconcile(inventories, player, cause)
        }
    }

    private fun rebaselineOpenContainer(player: Player) {
        val top = player.openInventory.topInventory
        val holder = top.toHolderId() ?: return
        if (holder is HolderId.Player) return
        services.differ.rebaseline(holder, top.toItemTotals())
    }
}
