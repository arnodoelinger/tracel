package com.tracel.plugin.importer.coreprotect.legacy

import com.tracel.annotations.Unstable

/**
 * Stand-ins for `Bukkit` enums that stopped being enums.
 *
 * A `CoreProtect` database written on an older server holds these as serialized enum constants, and `Java` will
 * not read one back into what is now an interface: the whole blob fails, and with it everything else it said.
 * Reading the constant into one of these keeps the blob and gives the registry key it meant.
 */
@Unstable
internal interface LegacyRegistry {
    /** The constant as it was serialized. */
    val name: String

    /** The registry key this constant stood for. */
    val key: String get() = "minecraft:${name.lowercase()}"

    companion object {
        val STAND_INS: Map<String, Class<*>> = mapOf(
            "org.bukkit.attribute.Attribute" to LegacyAttribute::class.java,
            $$"org.bukkit.entity.Villager$Profession" to LegacyVillagerProfession::class.java,
            $$"org.bukkit.entity.Villager$Type" to LegacyVillagerType::class.java,
            $$"org.bukkit.entity.Cat$Type" to LegacyCatType::class.java,
            $$"org.bukkit.entity.Frog$Variant" to LegacyFrogVariant::class.java,
        )
    }
}
