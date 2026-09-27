package com.tracel.plugin.adapter.block.special

import com.tracel.annotations.Unstable
import com.tracel.plugin.adapter.block.CargoSlots
import com.tracel.plugin.adapter.block.capability.cargo.CargoSurface
import org.bukkit.block.Block
import org.bukkit.block.BlockState
import org.bukkit.block.Campfire
import org.bukkit.inventory.ItemStack

/**
 * Campfire.
 *
 * @see Campfire
 */
@Unstable
internal object CampfireCargo : CargoSurface {
    override fun of(state: BlockState): CargoSlots? {
        if (state !is Campfire) return null
        return CampfireHeld(state)
    }

    fun resync(block: Block) {
        val state = runCatching { block.getState(false) }.getOrNull() as? Campfire ?: return
        runCatching { state.update(true, false) }
    }
}

@Unstable
private class CampfireHeld(private val campfire: Campfire) : CargoSlots {
    override val size: Int get() = campfire.size
    override fun get(slot: Int): ItemStack? = campfire.getItem(slot)
    override fun set(slot: Int, stack: ItemStack?) = campfire.setItem(slot, stack ?: ItemStack.empty())
}
