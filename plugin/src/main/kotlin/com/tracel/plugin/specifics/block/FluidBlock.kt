package com.tracel.plugin.specifics.block

import com.tracel.plugin.specifics.GameMaterial
import com.tracel.plugin.specifics.materialsOf
import org.bukkit.Material

/** Blocks that are nothing but fluid. */
internal enum class FluidBlock(override val material: Material?, val id: String, val pours: Boolean) : GameMaterial {
    WATER(Material.WATER, "water", pours = true),
    LAVA(Material.LAVA, "lava", pours = true),
    BUBBLE_COLUMN(Material.BUBBLE_COLUMN, "bubble_column", pours = false);

    companion object {
        val materials: Set<Material> = materialsOf(entries)
        val ids: Set<String> = entries.mapTo(HashSet()) { it.id }
        val pouring: Set<Material> = materialsOf(entries.filter { it.pours })
    }
}

/** Whether this material is a fluid block: water, lava, a bubble column. */
internal fun Material.isFluidBlock(): Boolean = this in FluidBlock.materials

/** Whether the block called [id], without a namespace, is a fluid block. */
internal fun isFluidId(id: String): Boolean = id in FluidBlock.ids

/** Whether this material is water or lava. */
internal fun Material.poursLikeFluid(): Boolean = this in FluidBlock.pouring
