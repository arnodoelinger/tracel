package com.tracel.plugin.importer.coreprotect

import com.tracel.annotations.Unstable

/**
 * Stand-ins for `Bukkit` enums that stopped being enums.
 *
 * A `CoreProtect` database written on an older server holds these as serialized enum constants, and `Java` will
 * not read one back into what is now an interface: the whole blob fails, and with it everything else it said.
 * Reading the constant into one of these keeps the blob and gives the registry key it meant.
 */
@Unstable
internal interface LegacyRegistryValue {
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

@Suppress("UNUSED")
internal enum class LegacyAttribute : LegacyRegistryValue {
    GENERIC_MAX_HEALTH,
    GENERIC_FOLLOW_RANGE,
    GENERIC_KNOCKBACK_RESISTANCE,
    GENERIC_MOVEMENT_SPEED,
    GENERIC_FLYING_SPEED,
    GENERIC_ATTACK_DAMAGE,
    GENERIC_ATTACK_KNOCKBACK,
    GENERIC_ATTACK_SPEED,
    GENERIC_ARMOR,
    GENERIC_ARMOR_TOUGHNESS,
    GENERIC_FALL_DAMAGE_MULTIPLIER,
    GENERIC_LUCK,
    GENERIC_MAX_ABSORPTION,
    GENERIC_SAFE_FALL_DISTANCE,
    GENERIC_SCALE,
    GENERIC_STEP_HEIGHT,
    GENERIC_GRAVITY,
    GENERIC_JUMP_STRENGTH,
    GENERIC_BURNING_TIME,
    GENERIC_EXPLOSION_KNOCKBACK_RESISTANCE,
    GENERIC_MOVEMENT_EFFICIENCY,
    GENERIC_OXYGEN_BONUS,
    GENERIC_WATER_MOVEMENT_EFFICIENCY,
    PLAYER_BLOCK_INTERACTION_RANGE,
    PLAYER_ENTITY_INTERACTION_RANGE,
    PLAYER_BLOCK_BREAK_SPEED,
    PLAYER_MINING_EFFICIENCY,
    PLAYER_SNEAKING_SPEED,
    PLAYER_SUBMERGED_MINING_SPEED,
    PLAYER_SWEEPING_DAMAGE_RATIO,
    HORSE_JUMP_STRENGTH,
    ZOMBIE_SPAWN_REINFORCEMENTS;

    /** `GENERIC_MAX_HEALTH` was `minecraft:max_health`: the word before the first underscore was only a group. */
    override val key: String get() = "minecraft:${name.substringAfter('_').lowercase()}"
}

@Suppress("UNUSED")
internal enum class LegacyVillagerProfession : LegacyRegistryValue {
    NONE,
    ARMORER,
    BUTCHER,
    CARTOGRAPHER,
    CLERIC,
    FARMER,
    FISHERMAN,
    FLETCHER,
    LEATHERWORKER,
    LIBRARIAN,
    MASON,
    NITWIT,
    SHEPHERD,
    TOOLSMITH,
    WEAPONSMITH,
}

@Suppress("UNUSED")
internal enum class LegacyVillagerType : LegacyRegistryValue {
    DESERT,
    JUNGLE,
    PLAINS,
    SAVANNA,
    SNOW,
    SWAMP,
    TAIGA
}

@Suppress("UNUSED")
internal enum class LegacyCatType : LegacyRegistryValue {
    TABBY,
    BLACK,
    RED,
    SIAMESE,
    BRITISH_SHORTHAIR,
    CALICO,
    PERSIAN,
    RAGDOLL,
    WHITE,
    JELLIE,
    ALL_BLACK,
}

@Suppress("UNUSED")
internal enum class LegacyFrogVariant : LegacyRegistryValue {
    TEMPERATE,
    WARM,
    COLD
}
