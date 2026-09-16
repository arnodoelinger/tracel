package com.tracel.plugin.listener.material.inventory

import com.tracel.annotations.CauseKind
import com.tracel.annotations.Observes
import com.tracel.engine.ledger.Ingredient
import com.tracel.engine.ledger.Product
import com.tracel.model.holder.HolderId
import com.tracel.model.id.Quantity
import com.tracel.plugin.TracelServices
import com.tracel.plugin.adapter.item.lostRelativeTo
import com.tracel.plugin.adapter.item.mergedWith
import com.tracel.plugin.adapter.entity.toBlockPos
import com.tracel.plugin.adapter.block.toHolderId
import com.tracel.plugin.adapter.item.toItemKey
import com.tracel.plugin.adapter.item.toItemTotals
import com.tracel.plugin.adapter.item.withCursor
import com.tracel.plugin.adapter.block.toBlockPos
import com.tracel.plugin.listener.TracelListener
import com.tracel.plugin.listener.support.BlockRelease
import com.tracel.plugin.listener.support.isLedgeredHolder
import java.util.logging.Level
import java.util.logging.Logger
import org.bukkit.block.Container
import org.bukkit.entity.Player
import org.bukkit.event.block.CrafterCraftEvent
import org.bukkit.event.inventory.CraftItemEvent
import org.bukkit.inventory.meta.Damageable

/** Craft listener. */
class CraftListener(services: TracelServices) : TracelListener(services) {
    private val logger = Logger.getLogger(CraftListener::class.java.name)

    @Observes
    fun onCraft(event: CraftItemEvent) {
        val player = event.whoClicked as? Player ?: return
        if (!player.isLedgeredHolder()) return
        val playerHolder = HolderId.Player(player.uniqueId)
        val before = event.inventory.matrix.toItemTotals()

        // Recipe result name, now: a tick later it still exists but we need it to tell craft output from hopper / mob / off-hand gains
        val produced = runCatching { event.recipe.result.type.name }.getOrNull()
        val productDamage = event.currentItem?.takeIf { it.type.maxDurability > 0 }?.let { (it.itemMeta as? Damageable)?.damage }

        // Player holders have no coords; without the bench (or feet for 2 x 2) "scope:" never sees "a:craft"
        val where = event.inventory.location?.block?.toBlockPos() ?: player.toBlockPos()

        later(player.location) {
            val matrix = event.inventory.matrix.toItemTotals()
            val consumed = before.lostRelativeTo(matrix)
            if (consumed.isEmpty()) return@later

            // Grid is part of the player snapshot (cursor too)
            val totals = player.inventory.toItemTotals().mergedWith(matrix).withCursor(player)
            val ingredients = consumed.map { (key, qty) -> Ingredient(playerHolder, key, Quantity(qty)) }
            val epochMillis = System.currentTimeMillis()

            owing {
                try {
                    material.craftedByPlayer(playerHolder, totals, produced, ingredients, where, epochMillis, productDamage) { gains ->
                        logger.log(
                            Level.FINE,
                            "craft by $playerHolder produced $gains distinct item keys and none " +
                                "uniquely matched ${produced ?: "an unknown recipe"}, not recorded",
                        )
                    }
                } catch (e: IllegalStateException) {
                    logger.log(Level.FINE, "untracked ingredient material for craft by $playerHolder, not recorded", e)
                }
            }
        }
    }

    /**
     * Auto-crafter.
     *
     * No [CraftItemEvent]. Record into the crafter; claim window for the eject;
     * hopper move stands if unclaimed.
     */
    @Observes
    fun onCrafter(event: CrafterCraftEvent) {
        val result = event.result
        if (result.type.isAir || result.amount <= 0) return
        val block = event.block
        val state = block.getState(false) as? Container ?: return
        val holder = block.toHolderId()
        val before = state.inventory.toItemTotals()
        val productKey = result.toItemKey()
        val productQty = result.amount.toLong()
        val at = block.toBlockPos()
        val epochMillis = System.currentTimeMillis()

        material.releasing(
            releases = listOf(BlockRelease(holder, block.world, block.x, block.y, block.z, mapOf(productKey to productQty))),
            cause = CauseKind.CRAFT,
            causedBy = null,
            at = at,
            epochMillis = epochMillis,
        )

        later(block.location) {
            val after = (block.getState(false) as? Container)?.inventory?.toItemTotals().orEmpty()
            val consumed = before.lostRelativeTo(after)
            if (consumed.isEmpty()) return@later
            val ingredients = consumed.map { (key, qty) -> Ingredient(holder, key, Quantity(qty)) }
            val product = Product(holder, productKey, Quantity(productQty))
            owing {
                try {
                    material.crafted(
                        ingredients = ingredients,
                        product = product,
                        causedBy = null,
                        at = at,
                        epochMillis = epochMillis,
                    )
                } catch (e: IllegalStateException) {
                    logger.log(Level.FINE, "untracked ingredient material for crafter at $at, not recorded", e)
                }
            }
        }
    }
}
