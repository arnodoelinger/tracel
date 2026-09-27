package com.tracel.plugin.adapter.block.capability.extras

import com.tracel.model.world.block.BlockExtras
import org.bukkit.Nameable
import org.bukkit.block.BlockState
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.BlockStateMeta
import org.bukkit.NamespacedKey
import org.bukkit.block.Crafter
import org.bukkit.persistence.PersistentDataType

internal val DISABLED_SLOTS: NamespacedKey = NamespacedKey("tracel", "disabled_slots")

private const val CRAFTER_SLOTS = 9

/**
 * Tile extras via the placement item's [BlockStateMeta].
 *
 * Signs, skulls, spawners, etc.
 *
 * Failures fall back to the name only.
 *
 * @see [BlockStateMeta]
 */
internal object BlockStateMetaExtras {
    /** [live] is the placed state [state] was copied from: a copy keeps no crafter slot states. */
    fun of(state: BlockState, live: BlockState = state): BlockExtras? {
        val carrier = ItemStack(PlacementItem.of(state))
        val meta = carrier.itemMeta as? BlockStateMeta ?: return NameableExtras.of(state)
        meta.blockState = state
        (state as? Nameable)?.customName()?.let { meta.customName(it) }
        (live as? Crafter)?.let { crafter ->
            val off = (0 until CRAFTER_SLOTS).filter { runCatching { crafter.isSlotDisabled(it) }.getOrDefault(false) }
            if (off.isNotEmpty()) meta.persistentDataContainer.set(DISABLED_SLOTS, PersistentDataType.INTEGER_ARRAY, off.toIntArray())
        }
        carrier.itemMeta = meta
        return BlockExtras.Opaque(carrier.serializeAsBytes())
    }
}
