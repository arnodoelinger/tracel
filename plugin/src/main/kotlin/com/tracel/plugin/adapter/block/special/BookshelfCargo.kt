package com.tracel.plugin.adapter.block.special

import com.tracel.annotations.Unstable
import com.tracel.plugin.adapter.block.CargoSlots
import com.tracel.plugin.adapter.block.capability.cargo.CargoSurface
import org.bukkit.block.Block
import org.bukkit.block.BlockState
import org.bukkit.block.ChiseledBookshelf
import org.bukkit.inventory.ItemStack
import org.bukkit.block.data.type.ChiseledBookshelf as ChiseledBookshelfData

/**
 * Chiseled bookshelf cargo.
 *
 * @see ChiseledBookshelf
 */
@Unstable
internal object BookshelfCargo : CargoSurface {
    override fun of(state: BlockState): CargoSlots? {
        if (state !is ChiseledBookshelf) return null
        return BookshelfHeld(state)
    }

    @Suppress("UsePropertyAccessSyntax")
    fun sync(block: Block) {
        val state = runCatching { block.getState(false) }.getOrNull() as? ChiseledBookshelf ?: return
        runCatching { state.setLastInteractedSlot(-1) }
    }
}

private class BookshelfHeld(private val shelf: ChiseledBookshelf) : CargoSlots {
    override val size: Int get() = live().inventory.size.coerceAtLeast(occupiedSlots())

    override fun get(slot: Int): ItemStack? = live().inventory.getItem(slot)

    override fun set(slot: Int, stack: ItemStack?) {
        live().inventory.setItem(slot, stack ?: ItemStack.empty())
    }

    private fun live(): ChiseledBookshelf {
        if (!shelf.isPlaced) return shelf
        return (shelf.block.getState(false) as? ChiseledBookshelf) ?: shelf
    }

    private fun occupiedSlots(): Int {
        val data = live().blockData as? ChiseledBookshelfData ?: return 0
        return data.maximumOccupiedSlots
    }
}
