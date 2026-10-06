package com.tracel.plugin.specifics.block

import com.tracel.plugin.specifics.GameMaterial
import com.tracel.plugin.specifics.materialsOf
import org.bukkit.Material

/** What water and lava leave behind where they meet, freeze or cool. */
internal enum class FluidMadeBlock(override val material: Material?) : GameMaterial {
    COBBLESTONE(Material.COBBLESTONE),
    STONE(Material.STONE),
    OBSIDIAN(Material.OBSIDIAN),
    BASALT(Material.BASALT),
    ICE(Material.ICE),
    FROSTED_ICE(Material.FROSTED_ICE);

    companion object {
        val materials: Set<Material> = materialsOf(entries)
    }
}
