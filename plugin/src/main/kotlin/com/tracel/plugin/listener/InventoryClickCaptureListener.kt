package com.tracel.plugin.listener

import com.tracel.annotations.CauseKind
import com.tracel.model.holder.HolderId
import com.tracel.plugin.TracelServices
import com.tracel.plugin.convert.toHolderId
import com.tracel.plugin.convert.toItemTotals
import com.tracel.plugin.convert.withCursor
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.inventory.Inventory
import org.bukkit.plugin.Plugin
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Captures the result of any inventory click by diffing contents afterward, never by
 * interpreting the click itself.
 */
class InventoryClickCaptureListener(
    private val services: TracelServices,
    private val plugin: Plugin,
) : Listener {
    private val logger = Logger.getLogger(InventoryClickCaptureListener::class.java.name)

    /**
     * The click event is fired before the inventory has actually changed, so we schedule a
     * one-tick delay to capture the result of the click instead of the state before it.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        val view = event.view
        val inventories = listOfNotNull(view.topInventory, view.bottomInventory).distinct()

        Bukkit.getRegionScheduler().runDelayed(plugin, player.location, { captureAll(inventories, player) }, 1L)
    }

    /**
     * Diff every inventory touched by this click, combine the results into one delta list, and
     * record it as a single capture.
     */
    private fun captureAll(inventories: List<Inventory>, player: Player) {
        val causedBy = HolderId.Player(player.uniqueId)

        // Every inventory this click could have touched is diffed and combined into one delta
        // list before it ever reaches TransactionBalancer — a chest losing 4 and the player
        // gaining 4 in the same click have to be balanced against each other as one "MOVE", not
        // recorded as two disconnected, unmatched "MINT" / "BURN" pairs.
        val deltas = inventories.flatMap { inventory ->
            val holder = inventory.toHolderId() ?: return@flatMap emptyList()
            var totals = inventory.toItemTotals()
            if (holder == causedBy) {
                // The cursor stack lives outside any Inventory Bukkit exposes — it's what a
                // player is physically holding mid-click. Left uncounted, picking an item up
                // (onto the cursor) and putting it down later — even in the very same click —
                // reads as an unmatched loss followed by an unmatched gain, when nothing
                // actually left the player at all. Folding it into their own totals is what
                // makes that invisible, the way it should be.
                totals = totals.withCursor(player)
            }
            services.differ.diff(holder, totals)
        }
        if (deltas.isEmpty()) return

        val epochMillis = System.currentTimeMillis()
        services.scope.launch {
            try {
                withContext(services.schedulers.storage) {
                    services.capture.record(deltas, epochMillis, CauseKind.PLAYER_ACTION, causedBy)
                }
            } catch (e: IllegalStateException) {
                // Same untracked-material gap HopperTransferListener has: a burn / withdraw
                // that touches material the ledger never saw enter (pre-existing inventory
                // contents, most commonly) throws instead of corrupting state. Until there is
                // a mint-on-first-sight story, this is expected, not a bug to crash a thread over.
                logger.log(Level.FINE, "untracked material in click by $causedBy, not recorded", e)
            }
        }
    }
}
