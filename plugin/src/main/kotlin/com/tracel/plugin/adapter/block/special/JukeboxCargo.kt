package com.tracel.plugin.adapter.block.special

import com.tracel.annotations.Unstable
import com.tracel.plugin.adapter.block.CargoSlots
import com.tracel.plugin.adapter.block.capability.cargo.CargoSurface
import org.bukkit.block.BlockState
import org.bukkit.block.Jukebox
import org.bukkit.inventory.ItemStack

/**
 * Jukebox cargo (disc).
 *
 * @see Jukebox
 */
@Unstable
internal object JukeboxCargo : CargoSurface {
    override fun of(state: BlockState): CargoSlots? {
        if (state !is Jukebox) return null
        return JukeboxHeld(state)
    }
}

private class JukeboxHeld(private val state: Jukebox) : CargoSlots {
    override val size: Int get() = 1

    override fun get(slot: Int): ItemStack? {
        if (slot != 0) return null
        val box = live()
        real(box.inventory.getItem(0))?.let { return it }
        real(box.record)?.let { return it }
        return null
    }

    @Suppress("UsePropertyAccessSyntax")
    override fun set(slot: Int, stack: ItemStack?) {
        if (slot != 0) return
        val record = stack ?: ItemStack.empty()
        val box = live()
        runCatching { box.stopPlaying() }
        box.setRecord(record)
        if (!box.isPlaced) return
        runCatching { box.update(true, false) }
    }

    private fun live(): Jukebox {
        if (!state.isPlaced) return state
        return (state.block.getState(false) as? Jukebox) ?: state
    }

    private fun real(stack: ItemStack?): ItemStack? =
        stack?.takeUnless { it.isEmpty || it.type.isAir }
}
