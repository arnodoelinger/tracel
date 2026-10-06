package com.tracel.plugin.specifics.block

import com.tracel.plugin.specifics.GameMaterial
import com.tracel.plugin.specifics.materialsOf
import org.bukkit.Material

/** Snow that turns the ground under it snowy. */
internal enum class SnowBlock(override val material: Material?, val id: String) : GameMaterial {
    SNOW(Material.SNOW, "snow"),
    SNOW_BLOCK(Material.SNOW_BLOCK, "snow_block"),
    POWDER_SNOW(Material.POWDER_SNOW, "powder_snow");

    companion object {
        val materials: Set<Material> = materialsOf(entries)
        val ids: Set<String> = entries.mapTo(HashSet()) { it.id }
    }
}

/** Whether [material] is a snow material. */
internal fun isSnowMaterial(material: Material): Boolean = material in SnowBlock.materials

/** Whether the block called [id], without a namespace, is snow. */
internal fun isSnowId(id: String): Boolean = id in SnowBlock.ids
