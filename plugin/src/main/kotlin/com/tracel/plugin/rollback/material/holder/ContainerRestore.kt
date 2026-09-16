package com.tracel.plugin.rollback.material.holder

import com.tracel.annotations.Unstable
import com.tracel.model.holder.HolderId
import com.tracel.model.item.ItemKey
import com.tracel.plugin.adapter.block.resyncCargo
import com.tracel.plugin.adapter.item.toItemTotals
import com.tracel.plugin.adapter.world.worldOf
import com.tracel.plugin.rollback.material.MaterialRestorer
import com.tracel.plugin.rollback.material.cargo.applyBookshelf
import com.tracel.plugin.rollback.material.cargo.applyCampfire
import com.tracel.plugin.rollback.material.cargo.applyJukebox
import com.tracel.plugin.rollback.material.cargo.applyLectern
import com.tracel.plugin.rollback.material.cargo.syncCargoFlags
import com.tracel.plugin.rollback.material.item.Moves
import com.tracel.plugin.rollback.material.item.WornStacks
import com.tracel.plugin.rollback.material.item.applyDelta
import com.tracel.plugin.rollback.material.spill.Spill
import com.tracel.plugin.rollback.material.spill.spillInRegion
import io.papermc.paper.block.TileStateInventoryHolder
import kotlinx.coroutines.withContext
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.BrewingStand
import org.bukkit.block.Campfire
import org.bukkit.block.ChiseledBookshelf
import org.bukkit.block.Jukebox
import org.bukkit.block.Lectern
import org.bukkit.inventory.InventoryHolder

private const val NOT_A_CONTAINER = "block is no longer a container"

private val RETRY_TICKS = longArrayOf(2, 4, 8, 16, 20)

/** Applies [deltas] to whatever container lives at [holder]. */
@Unstable
internal suspend fun MaterialRestorer.applyToContainer(
    holder: HolderId.Block,
    deltas: Map<ItemKey, Long>,
    forms: Map<ItemKey, ByteArray>,
    sink: MutableCollection<Spill>,
    asOf: Long? = null,
    worn: WornStacks? = null,
): String? {
    suspend fun fill(): String? = withContext(services.schedulers.region(holder)) {
        val world = worldOf(holder.world) ?: return@withContext "world is not loaded"
        val block = world.getBlockAt(holder.x, holder.y, holder.z)
        val state = block.getState(false)
        val at = Location(world, holder.x + 0.5, holder.y + 1.0, holder.z + 0.5)
        val moves = Moves()

        if (state is Campfire) {
            applyCampfire(state, deltas, forms, moves)
            block.resyncCargo()
            spillInRegion(holder, moves, world, at, sink)
            services.differ.rebaseline(holder, Array(state.size) { state.getItem(it) }.toItemTotals())
            return@withContext moves.reason
        }

        if (state is Jukebox) {
            applyJukebox(state, deltas, forms, moves)
            spillInRegion(holder, moves, world, at, sink)
            syncCargoFlags(state)
            services.differ.rebaseline(holder, state.inventory.toItemTotals())
            return@withContext moves.reason
        }

        if (state is Lectern) {
            applyLectern(state, deltas, forms, moves)
            spillInRegion(holder, moves, world, at, sink)
            syncCargoFlags(state)
            services.differ.rebaseline(holder, state.inventory.toItemTotals())
            return@withContext moves.reason
        }

        if (state is ChiseledBookshelf) {
            val preferredSlots = asOf?.let { services.containerSlots.layoutAt(holder, it) }.orEmpty()
            applyBookshelf(state, deltas, forms, moves, preferredSlots)
            spillInRegion(holder, moves, world, at, sink)
            runCatching { state.lastInteractedSlot = -1 }
            services.differ.rebaseline(holder, state.inventory.toItemTotals())
            return@withContext moves.reason
        }

        val inventory = (state as? TileStateInventoryHolder)?.inventory
            ?: (state as? InventoryHolder)?.inventory
            ?: return@withContext NOT_A_CONTAINER

        val preferredSlots = asOf?.let { services.containerSlots.layoutAt(holder, it) }
            ?.groupBy { it.itemKey }
            .orEmpty()
        for ((itemKey, delta) in deltas) {
            applyDelta(itemKey, delta, forms[itemKey], moves, inventory, preferredSlots[itemKey].orEmpty(), worn)
        }
        if (state is BrewingStand) primeBrewingStandFuel(state)
        spillInRegion(holder, moves, world, at, sink)
        syncCargoFlags(state)
        services.differ.rebaseline(holder, inventory.toItemTotals())
        moves.reason
    }

    val first = fill()
    if (first != NOT_A_CONTAINER) return first

    // Not a container (!) yet
    for (ticks in RETRY_TICKS) {
        awaitHolderTicks(holder, ticks)
        val again = fill()
        if (again != NOT_A_CONTAINER) return again
    }
    return first
}

private fun primeBrewingStandFuel(state: BrewingStand) {
    if (state.fuelLevel > 0) return
    if (state.inventory.fuel?.type != Material.BLAZE_POWDER) return
    state.fuelLevel = 20
}
