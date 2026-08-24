package com.tracel.plugin.listener.capture

import com.tracel.annotations.CauseKind
import com.tracel.model.holder.HolderId
import com.tracel.model.item.ItemKey
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
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryCreativeEvent
import org.bukkit.event.inventory.InventoryType
import org.bukkit.inventory.CraftingInventory
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
        // Excludes only crafting-matrix / result-slot clicks, left to CraftCaptureListener instead.
        // A player's own inventory screen always has the personal 2 x 2 grid on top, so a naive check
        // for "view has a CraftingInventory" would blind this to every ordinary click a player makes.
        if (event.slotType == InventoryType.SlotType.CRAFTING || event.slotType == InventoryType.SlotType.RESULT) return
        // InventoryCreativeEvent extends InventoryClickEvent and would otherwise reach this handler
        // too, double-diffing the same click alongside onCreative below.
        if (event is InventoryCreativeEvent) return

        val player = event.whoClicked as? Player ?: return
        val view = event.view
        val inventories = listOfNotNull(view.topInventory, view.bottomInventory).distinct()

        Bukkit.getRegionScheduler().runDelayed(plugin, player.location, { captureAll(inventories, player) }, 1L)
    }

    /**
     * A creative-mode grab / drop is invisible to the ledger otherwise. Reuses the same
     * diff-based capture as an ordinary click — it's still just "the player's inventory changed."
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onCreative(event: InventoryCreativeEvent) {
        val player = event.whoClicked as? Player ?: return
        val view = event.view
        val inventories = listOfNotNull(view.topInventory, view.bottomInventory).distinct()

        Bukkit.getRegionScheduler().runDelayed(plugin, player.location, { captureAll(inventories, player) }, 1L)
    }

    /**
     * A safety net for whatever a click event alone doesn't cover — most notably, empirically,
     * a creative player dragging a cursor stack out of an open inventory into the void to delete
     * it.
     *
     * That produces no diffable click through [onClick] / [onCreative] at all.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onClose(event: InventoryCloseEvent) {
        val player = event.player as? Player ?: return
        Bukkit.getRegionScheduler().runDelayed(plugin, player.location, { captureAll(listOf(player.inventory), player) }, 1L)
    }

    /**
     * Diff every inventory touched by this click, combine the results into one delta list, and
     * record it as a single capture.
     */
    private fun captureAll(inventories: List<Inventory>, player: Player) {
        val causedBy = HolderId.Player(player.uniqueId)

        // Grouped by resolved holder, not by Inventory object identity. A player's own inventory
        // screen has the personal 2 x 2 crafting grid as topInventory alongside the real inventory
        // as bottomInventory, and both resolve to the same HolderId.Player.
        //
        // Diffing them as two separate calls against the same per-holder snapshot corrupts it:
        // whichever runs second sees the other's just-written totals as its "before" state and
        // reports the entire real inventory as vanishing. Summing totals first and diffing once
        // per holder avoids that.
        val totalsByHolder = mutableMapOf<HolderId, MutableMap<ItemKey, Long>>()
        for (inventory in inventories) {
            val holder = inventory.toHolderId() ?: continue
            // A CraftingInventory's .contents includes its result slot — a preview of what a valid
            // recipe would produce, materializing the instant the grid is filled, not something the
            // player actually holds yet. Counted here it reads as a phantom gain, poisoning the
            // snapshot so the real CraftItemEvent right after sees zero net gain. matrix.toItemTotals()
            // exists for exactly this reason — CraftCaptureListener already uses it.
            var totals = (inventory as? CraftingInventory)?.matrix?.toItemTotals() ?: inventory.toItemTotals()
            if (holder == causedBy) {
                // The cursor stack lives outside any Inventory Bukkit exposes — it's what a player
                // is physically holding mid-click. Left uncounted, picking an item up and putting it
                // down later reads as an unmatched loss followed by an unmatched gain.
                totals = totals.withCursor(player)
            }
            val merged = totalsByHolder.getOrPut(holder) { mutableMapOf() }
            for ((key, qty) in totals) merged.merge(key, qty, Long::plus)
        }

        // Reading the live inventories had to happen here, on the region thread that owns them
        val epochMillis = System.currentTimeMillis()
        services.scope.launch {
            try {
                withContext(services.schedulers.storage) {
                    // Combined into one delta list before it reaches transaction balancer. A chest
                    // losing 4 and the player gaining 4 in the same click have to balance as one "MOVE".
                    val deltas = totalsByHolder.flatMap { (holder, totals) -> services.differ.diff(holder, totals) }
                    if (deltas.isEmpty()) return@withContext
                    services.capture.record(deltas, epochMillis, CauseKind.PLAYER_ACTION, causedBy)
                }
            } catch (e: IllegalStateException) {
                // Same untracked-material gap HopperTransferListener has: a burn / withdraw that
                // touches material the ledger never saw enter throws instead of corrupting state.
                // Expected until there's a mint-on-first-sight story, not a bug to crash a thread over.
                logger.log(Level.FINE, "untracked material in click by $causedBy, not recorded", e)
            }
        }
    }
}
