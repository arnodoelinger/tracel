package com.tracel.plugin.adapter.block.special

import com.tracel.annotations.Unstable
import org.bukkit.block.BlockState
import org.bukkit.block.Jukebox
import org.bukkit.inventory.ItemStack

/**
 * Jukebox record.
 *
 * @see Jukebox
 */
@Unstable
@Suppress("UsePropertyAccessSyntax")
internal object JukeboxRecord {
    // Warning: never Inventory.clear a jukebox snapshot.
    // That calls JukeboxSongPlayer.stop and NPEs when the copy has no level.
    fun clear(state: BlockState): Boolean {
        if (state !is Jukebox) return false
        state.setRecord(ItemStack.empty())
        return true
    }
}
