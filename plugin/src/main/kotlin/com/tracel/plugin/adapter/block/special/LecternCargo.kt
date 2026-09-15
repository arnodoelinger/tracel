package com.tracel.plugin.adapter.block.special

import com.tracel.annotations.Unstable
import com.tracel.plugin.adapter.block.CargoSlots
import com.tracel.plugin.adapter.block.capability.cargo.CargoSurface
import org.bukkit.block.BlockState
import org.bukkit.block.Lectern
import org.bukkit.block.data.type.Lectern as LecternData
import org.bukkit.inventory.ItemStack

/**
 * Lectern cargo (book).
 *
 * @see Lectern
 */
@Unstable
internal object LecternCargo : CargoSurface {
    override fun of(state: BlockState): CargoSlots? {
        if (state !is Lectern) return null
        return LecternHeld(state)
    }
}

private class LecternHeld(private val lectern: Lectern) : CargoSlots {
    override val size: Int get() = 1

    override fun get(slot: Int): ItemStack? {
        if (slot != 0) return null
        val book = lectern.inventory.getItem(0)
        return book.takeUnless { it == null || it.isEmpty || it.type.isAir }
    }

    override fun set(slot: Int, stack: ItemStack?) {
        if (slot != 0) return
        lectern.inventory.setItem(0, stack ?: ItemStack.empty())
        val data = lectern.blockData as? LecternData ?: return
        data.setHasBook(stack != null && !stack.isEmpty && !stack.type.isAir)
        lectern.blockData = data
        if (lectern.isPlaced) runCatching { lectern.update(true, false) }
    }
}
