package com.tracel.plugin.listener.material.inventory

import com.tracel.annotations.Observes
import com.tracel.annotations.Priority
import com.tracel.engine.ledger.craft.Ingredient
import com.tracel.engine.ledger.craft.Product
import com.tracel.model.cause.CauseKind
import com.tracel.model.flow.Flow
import com.tracel.model.flow.FlowKind
import com.tracel.model.holder.HolderId
import com.tracel.model.item.ItemKey
import com.tracel.model.item.Quantity
import com.tracel.model.world.BlockPos
import com.tracel.plugin.TracelServices
import com.tracel.plugin.adapter.block.container
import com.tracel.plugin.adapter.block.toBlockPos
import com.tracel.plugin.adapter.block.toHolderId
import com.tracel.plugin.adapter.entity.toBlockPos
import com.tracel.plugin.adapter.item.*
import com.tracel.plugin.listener.TracelListener
import com.tracel.plugin.listener.support.drop.BlockRelease
import com.tracel.plugin.listener.support.drop.CraftDrop
import com.tracel.plugin.listener.support.flow.isLedgeredHolder
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.Container
import org.bukkit.block.data.type.Crafter
import org.bukkit.entity.Player
import org.bukkit.event.block.CrafterCraftEvent
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.CraftItemEvent
import org.bukkit.inventory.CraftingInventory
import org.bukkit.inventory.meta.Damageable
import java.util.logging.Level
import java.util.logging.Logger

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
        val productDamage =
            event.currentItem?.takeIf { it.type.maxDurability > 0 }?.let { (it.itemMeta as? Damageable)?.damage }

        // Player holders have no coords; without the bench (or feet for 2 x 2) "scope:" never sees "a:craft"
        val where = event.inventory.location?.block?.toBlockPos() ?: player.toBlockPos()
        if (event.click == ClickType.DROP || event.click == ClickType.CONTROL_DROP) CraftDrop.expect(player.uniqueId)

        material.craftOwed(player.uniqueId)
        later(player) {
            try {
                book(player, event.inventory, playerHolder, before, produced, where, productDamage)
            } finally {
                material.craftBooked(player.uniqueId)
            }
        }
    }

    private fun book(
        player: Player,
        grid: CraftingInventory,
        playerHolder: HolderId.Player,
        before: Map<ItemKey, Long>,
        produced: String?,
        where: BlockPos,
        productDamage: Int?,
    ) {
        val thrown = CraftDrop.take(player.uniqueId)
        val matrix = grid.matrix.toItemTotals()
        val consumed = before.lostRelativeTo(matrix)
        if (consumed.isEmpty()) return

        // Grid is part of the player snapshot (cursor too)
        val totals = player.inventory.toItemTotals().mergedWith(matrix).withCursor(player)
        val ingredients = consumed.map { (key, qty) -> Ingredient(playerHolder, key, Quantity(qty)) }
        val epochMillis = System.currentTimeMillis()

        // Diffed here, in click order: on the storage thread a second craft had already moved the snapshot on
        val seeded = services.differ.diffIfSeeded(playerHolder, totals)

        owing {
            try {
                material.craftedByPlayer(
                    playerHolder,
                    totals,
                    seeded,
                    produced,
                    ingredients,
                    thrown,
                    where,
                    epochMillis,
                    productDamage
                ) { gains ->
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

    /**
     * Auto-crafter.
     *
     * No [CraftItemEvent]. Record into the crafter; claim window for the eject;
     * hopper move stands if unclaimed.
     */
    @Observes(priority = Priority.HIGHEST)
    fun holdCrafterWhileRestoring(event: CrafterCraftEvent) {
        if (services.frozen.isFrozen(event.block.toHolderId())) event.isCancelled = true
    }

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

        val into = crafterFront(block)?.let { front -> block.getRelative(front) }?.takeIf { it.container() != null }
            ?.toHolderId()
        if (into == null) {
            material.releasing(
                releases = listOf(
                    BlockRelease(
                        holder,
                        block.world,
                        block.x,
                        block.y,
                        block.z,
                        mapOf(productKey to productQty)
                    )
                ),
                cause = CauseKind.CRAFT,
                causedBy = null,
                at = at,
                epochMillis = epochMillis,
            )
        }

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
                    if (into != null) {
                        material.adjust(into, productKey, productQty)
                        services.capture.recordDirect(
                            listOf(Flow(productKey, Quantity(productQty), holder, into, FlowKind.MOVE)),
                            epochMillis, CauseKind.CRAFT, null, at,
                        )
                    }
                } catch (e: IllegalStateException) {
                    logger.log(Level.FINE, "untracked ingredient material for crafter at $at, not recorded", e)
                }
            }
        }
    }
}

/** Which way a crafter ejects: the first half of its orientation, `NORTH_UP` faces north. */
private fun crafterFront(block: Block): BlockFace? {
    val data = block.blockData as? Crafter ?: return null
    return runCatching { BlockFace.valueOf(data.orientation.name.substringBefore('_')) }.getOrNull()
}
