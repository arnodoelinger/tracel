package com.tracel.plugin.specifics.block

import org.bukkit.Material

/** Copper aging and waxing: the prefixes a material name gains along the way. */
internal object Weathering {
    private val STAGE = Regex("^(WAXED_)?(EXPOSED_|WEATHERED_|OXIDIZED_)?")

    /** The name of [material] with its waxing and weathering stage taken off. */
    fun coreOf(material: Material): String = STAGE.replace(material.name, "")

    /** Whether [a] and [b] are one block at different stages. */
    fun sameCore(a: Material, b: Material): Boolean = coreOf(a) == coreOf(b)
}
