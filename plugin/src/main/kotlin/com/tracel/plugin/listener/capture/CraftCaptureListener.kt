package com.tracel.plugin.listener.capture

import com.tracel.engine.ledger.Ingredient
import com.tracel.engine.ledger.Product
import com.tracel.model.holder.HolderId
import com.tracel.model.id.Quantity
import com.tracel.plugin.TracelServices
import com.tracel.plugin.convert.lostRelativeTo
import com.tracel.plugin.convert.toItemTotals
import com.tracel.plugin.convert.withCursor
import kotlinx.coroutines.launch
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.inventory.CraftItemEvent
import org.bukkit.plugin.Plugin
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Captures a craft as one atomic [com.tracel.model.flow.FlowKind.TRANSFORM_IN] / `TRANSFORM_OUT`
 * pair instead of the disconnected `BURN` + `MINT` a generic click diff would produce.
 *
 * Diff-based. Trusting `event.recipe`'s declared amounts breaks for shift-click "craft as many as
 * possible", which fires one event for the whole batch.
 */
class CraftCaptureListener(
    private val services: TracelServices,
    private val plugin: Plugin,
) : Listener {
    private val logger = Logger.getLogger(CraftCaptureListener::class.java.name)

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onCraft(event: CraftItemEvent) {
        val player = event.whoClicked as? Player ?: return
        val playerHolder = HolderId.Player(player.uniqueId)
        val before = event.inventory.matrix.toItemTotals()

        Bukkit.getRegionScheduler().runDelayed(plugin, player.location, {
            val consumed = before.lostRelativeTo(event.inventory.matrix.toItemTotals())
            if (consumed.isEmpty()) return@runDelayed

            val totals = player.inventory.toItemTotals().withCursor(player)
            val ingredients = consumed.map { (key, qty) -> Ingredient(playerHolder, key, Quantity(qty)) }
            val epochMillis = System.currentTimeMillis()

            services.scope.launch {
                try {
                    services.atomically {
                        val gains = services.differ.diff(playerHolder, totals).filter { it.delta > 0 }
                        if (gains.size != 1) {
                            logger.log(Level.FINE, "craft by $playerHolder produced more than one distinct item key, not recorded")
                            return@atomically
                        }
                        val product = Product(playerHolder, gains.single().itemKey, Quantity(gains.single().delta))
                        services.capture.recordCraft(ingredients, product, epochMillis, playerHolder)
                    }
                } catch (e: IllegalStateException) {
                    logger.log(Level.FINE, "untracked ingredient material for craft by $playerHolder, not recorded", e)
                }
            }
        }, 1L) // TODO: https://www.youtube.com/watch?v=PF8d1r-jTN8; should be analyzed in future
    }
}
