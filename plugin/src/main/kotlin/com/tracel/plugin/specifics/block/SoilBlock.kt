package com.tracel.plugin.specifics.block

import com.tracel.plugin.specifics.GameMaterial
import com.tracel.plugin.specifics.materialsOf
import org.bukkit.Material

/** Ground that turns into other ground by itself: grass spreads, farmland dries, paths get trampled. */
internal enum class SoilBlock(override val material: Material?) : GameMaterial {
    DIRT(Material.DIRT),
    GRASS_BLOCK(Material.GRASS_BLOCK),
    FARMLAND(Material.FARMLAND),
    DIRT_PATH(Material.DIRT_PATH),
    MYCELIUM(Material.MYCELIUM),
    PODZOL(Material.PODZOL),
    NETHERRACK(Material.NETHERRACK),
    CRIMSON_NYLIUM(Material.CRIMSON_NYLIUM),
    WARPED_NYLIUM(Material.WARPED_NYLIUM),
    MUD(Material.MUD);

    companion object {
        val materials: Set<Material> = materialsOf(entries)
    }
}
