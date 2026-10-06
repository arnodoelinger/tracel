package com.tracel.plugin.specifics.block

/**
 * Block state properties the world flips by itself: a crop ages, a lamp lights, leaves count their distance.
 *
 * Two states that differ only in these are the same block to a rollback.
 */
internal val COSMETIC_PROPERTIES: Set<String> = setOf(
    "snowy", "age", "moisture", "power", "powered", "lit", "unstable", "triggered",
    "occupied", "berries", "distance", "persistent", "bloom", "can_summon", "shrieking",
    "hatch", "eggs", "dusted", "delay", "locked", "level",
)

/** Ground that turns into other ground by itself, by block id without a namespace. */
internal enum class GroundBlock(val id: String) {
    DIRT("dirt"),
    GRASS_BLOCK("grass_block"),
    MYCELIUM("mycelium"),
    PODZOL("podzol"),
    DIRT_PATH("dirt_path"),
    COARSE_DIRT("coarse_dirt"),
    ROOTED_DIRT("rooted_dirt");

    companion object {
        val ids: Set<String> = entries.mapTo(HashSet()) { it.id }
    }
}

/** The prefixes a copper block id gains as it weathers. */
internal val COPPER_WEATHER_PREFIXES: List<String> = listOf("exposed_", "weathered_", "oxidized_")

/**
 * What copper block [name] is, whatever its wax and weathering: `waxed_exposed_cut_copper` is `cut_copper`.
 *
 * @return `null` for anything that is not a copper block that weathers
 */
internal fun copperCore(name: String): String? {
    var s = name
    if (s.startsWith("waxed_")) s = s.removePrefix("waxed_")
    for (prefix in COPPER_WEATHER_PREFIXES) {
        if (s.startsWith(prefix)) s = s.removePrefix(prefix)
    }
    if (s == "copper_block") s = "copper"
    return s.takeIf { ("copper" in it || it == "lightning_rod") && "ore" !in it && it != "raw_copper_block" }
}
