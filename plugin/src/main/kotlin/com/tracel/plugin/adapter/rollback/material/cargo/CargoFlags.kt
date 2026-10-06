package com.tracel.plugin.adapter.rollback.material.cargo

import io.papermc.paper.block.TileStateInventoryHolder
import org.bukkit.block.BlockState
import org.bukkit.block.Jukebox
import org.bukkit.block.Lectern
import org.bukkit.block.data.type.ChiseledBookshelf
import org.bukkit.block.data.type.Jukebox as JukeboxData
import org.bukkit.block.data.type.Lectern as LecternData
import org.bukkit.inventory.ItemStack

/** Sync cargo flags. */
@Suppress("UsePropertyAccessSyntax")
internal fun syncCargoFlags(state: BlockState) {
    val inventory = (state as? TileStateInventoryHolder)?.inventory ?: return
    val saved = Array(inventory.size) { inventory.getItem(it)?.clone() }
    val data = state.blockData
    when (data) {
        // NMS setItem already wrote occupancy. Replaying a pre-take CraftBlockState
        // puts slot_occupied back and leaves a book on the shelf.
        is ChiseledBookshelf -> return
        is JukeboxData -> {
            val disc = (state as? Jukebox)?.record ?: saved.getOrNull(0)
            data.setHasRecord(disc.isReal())
        }

        is LecternData -> {
            val book = saved.getOrNull(0)
            data.setHasBook(book.isReal())
        }

        else -> return
    }
    state.blockData = data
    state.update(true, false)
    val liveState = state.block.getState(false)
    val live = (liveState as? TileStateInventoryHolder)?.inventory ?: return
    for (i in saved.indices) {
        if (i < live.size) live.setItem(i, saved[i] ?: ItemStack.empty())
    }
    when (liveState) {
        is Jukebox -> liveState.setRecord(saved.getOrNull(0) ?: ItemStack.empty())
        is Lectern -> liveState.inventory.setItem(0, saved.getOrNull(0))
        else -> Unit
    }
}

private fun ItemStack?.isReal(): Boolean =
    this != null && !isEmpty && !type.isAir
