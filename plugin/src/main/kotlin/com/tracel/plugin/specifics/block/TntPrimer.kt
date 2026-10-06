package com.tracel.plugin.specifics.block

import com.tracel.plugin.specifics.GameMaterial
import com.tracel.plugin.specifics.materialsOf
import org.bukkit.Material

/** Redstone parts that set TNT off the moment they are placed next to it. Buttons and plates come by name. */
internal enum class TntPrimer(override val material: Material?) : GameMaterial {
    REDSTONE_BLOCK(Material.REDSTONE_BLOCK),
    OBSERVER(Material.OBSERVER),
    LEVER(Material.LEVER),
    REDSTONE_TORCH(Material.REDSTONE_TORCH),
    REDSTONE_WALL_TORCH(Material.REDSTONE_WALL_TORCH),
    TRIPWIRE_HOOK(Material.TRIPWIRE_HOOK);

    companion object {
        val materials: Set<Material> = materialsOf(entries)
    }
}

/** Whether a player presses this by clicking it. */
internal fun Material.isButtonOrLever(): Boolean = this == Material.LEVER || name.endsWith("_BUTTON")

/** Whether this is pressed by standing on it. */
internal fun Material.isPressurePlate(): Boolean = name.endsWith("_PRESSURE_PLATE")

/** Whether placing this next to TNT can set it off. */
internal fun Material.canPrimeTnt(): Boolean =
    this in TntPrimer.materials || name.endsWith("_BUTTON") || name.endsWith("_PRESSURE_PLATE")
