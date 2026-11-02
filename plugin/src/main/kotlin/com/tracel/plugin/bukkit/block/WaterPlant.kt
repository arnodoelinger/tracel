package com.tracel.plugin.bukkit.block

import com.tracel.plugin.bukkit.GameMaterial
import com.tracel.plugin.bukkit.materialsOf
import org.bukkit.Material

/** Plants that only exist under water and are water themselves: no `waterlogged` property, always wet. */
internal enum class WaterPlant(override val material: Material?) : GameMaterial {
    KELP(Material.KELP),
    KELP_PLANT(Material.KELP_PLANT),
    SEAGRASS(Material.SEAGRASS),
    TALL_SEAGRASS(Material.TALL_SEAGRASS);

    companion object {
        val materials: Set<Material> = materialsOf(entries)
    }
}
