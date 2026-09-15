package com.tracel.plugin.adapter.block.special

import com.tracel.annotations.Unstable
import com.tracel.plugin.adapter.block.CargoSlots
import com.tracel.plugin.adapter.block.capability.cargo.CargoSurface
import org.bukkit.block.BlockState
import org.bukkit.block.BrushableBlock
import org.bukkit.inventory.ItemStack

/**
 * Suspicious sand / gravel.
 *
 * @see BrushableBlock
 */
@Unstable
internal object BrushableCargo : CargoSurface {
    override fun of(state: BlockState): CargoSlots? {
        if (state !is BrushableBlock) return null
        return BrushableHeld(state)
    }
}

@Unstable
@Suppress("UsePropertyAccessSyntax")
private class BrushableHeld(private val block: BrushableBlock) : CargoSlots {
    override val size: Int get() = 1
    override fun get(slot: Int): ItemStack? = if (slot == 0) block.item else null
    override fun set(slot: Int, stack: ItemStack?) {
        if (slot == 0) block.setItem(stack ?: ItemStack.empty())
    }
}
