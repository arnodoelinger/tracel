package com.tracel.plugin.specifics.inventory

import com.tracel.plugin.specifics.GameMaterial
import com.tracel.plugin.specifics.materialsOf
import org.bukkit.Material

/** Where things go in a brewing stand. */
internal enum class BrewingSlot(val slots: List<Int>) {
    BOTTLES(listOf(0, 1, 2)),
    INGREDIENT(listOf(3)),
    FUEL(listOf(4));

    companion object {
        fun of(material: Material): BrewingSlot = when {
            material == BREWING_FUEL -> FUEL
            material in BrewingBottle.materials -> BOTTLES
            else -> INGREDIENT
        }
    }
}

/** What a brewing stand's bottle slots take. */
internal enum class BrewingBottle(override val material: Material?) : GameMaterial {
    POTION(Material.POTION),
    SPLASH_POTION(Material.SPLASH_POTION),
    LINGERING_POTION(Material.LINGERING_POTION),
    GLASS_BOTTLE(Material.GLASS_BOTTLE);

    companion object {
        /** Every bottle this server has. */
        val materials: Set<Material> = materialsOf(entries)
    }
}

/** What a brewing stand burns. */
internal val BREWING_FUEL: Material = Material.BLAZE_POWDER

/** How many brews one piece of [BREWING_FUEL] pays for. */
internal const val BREWING_FUEL_CHARGES: Int = 20
