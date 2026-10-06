package com.tracel.plugin.specifics.block

import com.tracel.plugin.specifics.GameMaterial
import com.tracel.plugin.specifics.materialsOf
import org.bukkit.Material

/**
 * Blocks that turn the water above them into a bubble column.
 *
 * @param drag whether the column pulls down (magma) rather than pushes up (soul sand)
 */
internal enum class BubbleMaker(override val material: Material?, val drag: Boolean) : GameMaterial {
    SOUL_SAND(Material.SOUL_SAND, drag = false),
    MAGMA_BLOCK(Material.MAGMA_BLOCK, drag = true);

    companion object {
        val materials: Set<Material> = materialsOf(entries)

        fun dragOf(material: Material): Boolean? = entries.firstOrNull { it.material == material }?.drag
    }
}

/** Whether this material makes a bubble column out of the water above it. */
internal fun Material.makesBubbles(): Boolean = this in BubbleMaker.materials

/** Soul sand, magma, and bubble columns: a column above them has to be told when they come or go. */
internal fun wakesBubbles(material: Material): Boolean =
    material.makesBubbles() || material == Material.BUBBLE_COLUMN
