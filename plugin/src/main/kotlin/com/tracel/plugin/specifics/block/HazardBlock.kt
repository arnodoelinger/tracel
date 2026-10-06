package com.tracel.plugin.specifics.block

import com.tracel.plugin.specifics.GameMaterial
import com.tracel.plugin.specifics.materialNamed
import com.tracel.plugin.specifics.materialsOf
import org.bukkit.Material

/** Blocks that hurt whoever stands in or on them. A rollback moves players out of these. */
internal enum class HazardBlock(override val material: Material?) : GameMaterial {
    LAVA(Material.LAVA),
    FIRE(Material.FIRE),
    SOUL_FIRE(Material.SOUL_FIRE),
    CACTUS(Material.CACTUS),
    MAGMA_BLOCK(Material.MAGMA_BLOCK),
    POWDER_SNOW(Material.POWDER_SNOW),
    SWEET_BERRY_BUSH(Material.SWEET_BERRY_BUSH),
    WITHER_ROSE(Material.WITHER_ROSE),
    CAMPFIRE(Material.CAMPFIRE),
    SOUL_CAMPFIRE(Material.SOUL_CAMPFIRE),
    POINTED_DRIPSTONE(Material.POINTED_DRIPSTONE),

    // Newer than the oldest supported version
    SULFUR_SPIKE(materialNamed("sulfur_spike"));

    companion object {
        val materials: Set<Material> = materialsOf(entries)
    }
}
