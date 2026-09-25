package com.tracel.plugin.rollback.structure.block

import com.tracel.annotations.Unstable
import com.tracel.model.world.block.BlockShape
import com.tracel.plugin.adapter.block.BlockDataCache
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.data.Ageable
import org.bukkit.block.data.Waterlogged

@Unstable
private val FLUID_MADE = setOf(
    Material.COBBLESTONE,
    Material.STONE,
    Material.OBSIDIAN,
    Material.BASALT,
    Material.ICE,
    Material.FROSTED_ICE
)

@Unstable
private val NATURAL = setOf(
    Material.FIRE,
    Material.SOUL_FIRE,
    Material.SNOW,
    Material.POWDER_SNOW,
    Material.ICE,
    Material.FROSTED_ICE,
    Material.BUBBLE_COLUMN,
    Material.KELP,
    Material.KELP_PLANT,
    Material.SEAGRASS,
    Material.TALL_SEAGRASS,
    Material.BAMBOO,
    Material.SUGAR_CANE,
    Material.CACTUS,
    Material.VINE,
    Material.CAVE_VINES,
    Material.CAVE_VINES_PLANT,
    Material.WEEPING_VINES,
    Material.WEEPING_VINES_PLANT,
    Material.TWISTING_VINES,
    Material.TWISTING_VINES_PLANT,
    Material.SMALL_AMETHYST_BUD,
    Material.MEDIUM_AMETHYST_BUD,
    Material.LARGE_AMETHYST_BUD,
    Material.AMETHYST_CLUSTER,
    Material.POINTED_DRIPSTONE,
    Material.CHORUS_PLANT,
    Material.CHORUS_FLOWER,
    Material.SCULK,
    Material.SCULK_VEIN,
    Material.MELON,
    Material.PUMPKIN,
    Material.COCOA,
    Material.SWEET_BERRY_BUSH,
    Material.NETHER_WART,
)

@Unstable
private val SOIL = setOf(
    Material.DIRT,
    Material.GRASS_BLOCK,
    Material.FARMLAND,
    Material.DIRT_PATH,
    Material.MYCELIUM,
    Material.PODZOL,
    Material.NETHERRACK,
    Material.CRIMSON_NYLIUM,
    Material.WARPED_NYLIUM,
    Material.MUD,
)

@Unstable
private val WEATHERING = Regex("^(WAXED_)?(EXPOSED_|WEATHERED_|OXIDIZED_)?")

/**
 * Whether what stands here is the world moving on from [expected] by itself (fluid, fire, gravity,
 * growth, weathering, a state flip) rather than somebody's later build.
 */
@Unstable
internal fun Block.drifted(expected: BlockShape): Boolean {
    val type = type
    if (type.isAir || isLiquid || type.hasGravity() || type in NATURAL) return true
    if (runCatching { isReplaceable }.getOrDefault(false)) return true
    val was = BlockDataCache.of(expected.data)?.material ?: return false
    if (type == was) return true
    val data = blockData
    if (data is Ageable || data is Waterlogged && data.isWaterlogged && was.isAir) return true
    if (was == Material.WATER || was == Material.LAVA || was.isAir) return type in FLUID_MADE
    if (type in SOIL && was in SOIL) return true
    return WEATHERING.replace(type.name, "") == WEATHERING.replace(was.name, "")
}
