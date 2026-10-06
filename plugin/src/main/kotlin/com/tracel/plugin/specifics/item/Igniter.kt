package com.tracel.plugin.specifics.item

import com.tracel.plugin.specifics.GameMaterial
import com.tracel.plugin.specifics.materialsOf
import org.bukkit.Material

/** Items that light a creeper's fuse when used on it. */
internal enum class Igniter(override val material: Material?) : GameMaterial {
    FLINT_AND_STEEL(Material.FLINT_AND_STEEL),
    FIRE_CHARGE(Material.FIRE_CHARGE);

    companion object {
        val materials: Set<Material> = materialsOf(entries)
    }
}

/** Whether using this item on a creeper sets it off. */
internal fun Material.ignitesCreepers(): Boolean = this in Igniter.materials
