package com.tracel.plugin.specifics.item

import com.tracel.plugin.specifics.GameMaterial
import com.tracel.plugin.specifics.materialsOf
import org.bukkit.Material

/** Buckets with nothing alive in them. Every other filled bucket puts a mob into the world. */
internal enum class LifelessBucket(override val material: Material?) : GameMaterial {
    WATER_BUCKET(Material.WATER_BUCKET),
    LAVA_BUCKET(Material.LAVA_BUCKET),
    MILK_BUCKET(Material.MILK_BUCKET),
    POWDER_SNOW_BUCKET(Material.POWDER_SNOW_BUCKET);

    companion object {
        val materials: Set<Material> = materialsOf(entries)
    }
}

/** Whether this is a bucket, empty or full. */
internal fun Material.isBucket(): Boolean = this == Material.BUCKET || name.endsWith("_BUCKET")

/** Whether using this item puts an entity into the world: a spawn egg, a cushion, a bucket of fish. */
internal fun Material.spawnsAnEntity(): Boolean = when {
    name.endsWith("_SPAWN_EGG") || name.endsWith("_CUSHION") -> true
    !name.endsWith("_BUCKET") -> false
    else -> this !in LifelessBucket.materials
}
