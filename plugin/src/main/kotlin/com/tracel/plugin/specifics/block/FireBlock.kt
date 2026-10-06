package com.tracel.plugin.specifics.block

import com.tracel.plugin.specifics.GameMaterial
import com.tracel.plugin.specifics.materialsOf
import org.bukkit.Material

/** Fire of any kind. */
internal enum class FireBlock(override val material: Material?) : GameMaterial {
    FIRE(Material.FIRE),
    SOUL_FIRE(Material.SOUL_FIRE);

    companion object {
        val materials: Set<Material> = materialsOf(entries)
    }
}

/** Whether this material is fire. */
internal fun Material.isFireBlock(): Boolean = this in FireBlock.materials
