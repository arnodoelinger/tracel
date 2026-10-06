package com.tracel.plugin.specifics.block

import com.tracel.plugin.specifics.GameMaterial
import com.tracel.plugin.specifics.materialNamed
import com.tracel.plugin.specifics.materialsOf
import org.bukkit.Material

/**
 * Blocks the world puts down by itself: fire, snow, ice, plants that grow, sculk that spreads.
 *
 * Standing where a rollback expected something else, one of these is the world moving on, not a later build.
 */
internal enum class NaturalBlock(override val material: Material?) : GameMaterial {
    FIRE(Material.FIRE),
    SOUL_FIRE(Material.SOUL_FIRE),
    SNOW(Material.SNOW),
    POWDER_SNOW(Material.POWDER_SNOW),
    ICE(Material.ICE),
    FROSTED_ICE(Material.FROSTED_ICE),
    BUBBLE_COLUMN(Material.BUBBLE_COLUMN),
    KELP(Material.KELP),
    KELP_PLANT(Material.KELP_PLANT),
    SEAGRASS(Material.SEAGRASS),
    TALL_SEAGRASS(Material.TALL_SEAGRASS),
    BAMBOO(Material.BAMBOO),
    SUGAR_CANE(Material.SUGAR_CANE),
    CACTUS(Material.CACTUS),
    VINE(Material.VINE),
    CAVE_VINES(Material.CAVE_VINES),
    CAVE_VINES_PLANT(Material.CAVE_VINES_PLANT),
    WEEPING_VINES(Material.WEEPING_VINES),
    WEEPING_VINES_PLANT(Material.WEEPING_VINES_PLANT),
    TWISTING_VINES(Material.TWISTING_VINES),
    TWISTING_VINES_PLANT(Material.TWISTING_VINES_PLANT),
    SMALL_AMETHYST_BUD(Material.SMALL_AMETHYST_BUD),
    MEDIUM_AMETHYST_BUD(Material.MEDIUM_AMETHYST_BUD),
    LARGE_AMETHYST_BUD(Material.LARGE_AMETHYST_BUD),
    AMETHYST_CLUSTER(Material.AMETHYST_CLUSTER),
    POINTED_DRIPSTONE(Material.POINTED_DRIPSTONE),
    CHORUS_PLANT(Material.CHORUS_PLANT),
    CHORUS_FLOWER(Material.CHORUS_FLOWER),
    SCULK(Material.SCULK),
    SCULK_VEIN(Material.SCULK_VEIN),
    MELON(Material.MELON),
    PUMPKIN(Material.PUMPKIN),
    COCOA(Material.COCOA),
    SWEET_BERRY_BUSH(Material.SWEET_BERRY_BUSH),
    NETHER_WART(Material.NETHER_WART),

    // Newer than the oldest supported version
    SULFUR_SPIKE(materialNamed("sulfur_spike"));

    companion object {
        /** Every natural block this server has. */
        val materials: Set<Material> = materialsOf(entries)
    }
}
