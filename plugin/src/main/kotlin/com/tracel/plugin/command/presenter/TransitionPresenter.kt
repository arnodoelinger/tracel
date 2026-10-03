package com.tracel.plugin.command.presenter

/** Block changes. */
internal object TransitionPresenter {
    /** [key] is the verb under `lookup.verb`; [result] says the row names the block it became, not the one it was. */
    class Hit(val key: String, val result: Boolean)

    private class Block(val id: String, val props: Map<String, String>) {
        fun int(name: String): Int? = props[name]?.toIntOrNull()
        fun flag(name: String): Boolean? = props[name]?.toBooleanStrictOrNull()
    }

    private val GROWTH = listOf("small_amethyst_bud", "medium_amethyst_bud", "large_amethyst_bud", "amethyst_cluster")
    private val TILLABLE = setOf("dirt", "grass_block", "dirt_path", "coarse_dirt")
    private val CROPS = setOf("wheat", "carrots", "potatoes", "beetroots", "nether_wart", "sweet_berry_bush", "cocoa")
    private val COOLED = setOf("obsidian", "cobblestone", "stone", "basalt")
    private val ICE = setOf("ice", "frosted_ice")
    private val STAGES = listOf("", "exposed_", "weathered_", "oxidized_")
    private val QUIET_POWER = listOf("comparator", "repeater", "observer", "rail", "tripwire", "daylight", "target")

    /** [before] and [after] are block data as logged, with their state. E.g. `minecraft:wheat[age=3]`. */
    fun of(before: String, after: String, byEntity: Boolean): Hit? {
        val from = parse(before)
        val to = parse(after)
        return if (from.id == to.id) state(from, to) else swap(from.id, to.id, byEntity)
    }

    private fun parse(data: String): Block {
        val id = data.substringBefore('[').removePrefix("minecraft:")
        val props = data.substringAfter('[', "").removeSuffix("]").split(',')
            .filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
        return Block(id, props)
    }

    private fun state(from: Block, to: Block): Hit? {
        val id = to.id
        flips(from, to, "open")?.let { return Hit(if (it) "opened" else "closed", false) }
        flips(from, to, "lit")?.let { return Hit(if (it) "lit" else "extinguished", false) }
        flips(from, to, "extended")?.let { return Hit(if (it) "extended" else "retracted", false) }
        flips(from, to, "has_record")?.let { return Hit(if (it) "loaded" else "unloaded", false) }
        flips(from, to, "has_book")?.let { return Hit(if (it) "loaded" else "unloaded", false) }
        flips(from, to, "eye")?.let { return if (it) Hit("filled", false) else null }
        flips(from, to, "berries")?.let { return Hit(if (it) "grew" else "picked", false) }
        flips(from, to, "powered")?.let {
            return if (QUIET_POWER.none(id::contains)) Hit(if (it) "activated" else "deactivated", false) else null
        }

        val bites = delta(from, to, "bites")
        if (bites != null && bites > 0) return Hit("ate", false)
        val age = delta(from, to, "age") ?: delta(from, to, "stage") ?: delta(from, to, "honey_level")
        if (age != null) return if (age > 0) Hit(
            "grew",
            false
        ) else if (id in CROPS || id == "beehive" || id == "bee_nest") Hit("harvested", false) else null
        val level = delta(from, to, "level")
        if (level != null && (id.endsWith("cauldron") || id == "composter")) return Hit(
            if (level > 0) "filled" else "emptied",
            false
        )
        return null
    }

    private fun flips(from: Block, to: Block, name: String): Boolean? {
        val was = from.flag(name) ?: return null
        val now = to.flag(name) ?: return null
        return if (was == now) null else now
    }

    private fun delta(from: Block, to: Block, name: String): Int? {
        val was = from.int(name) ?: return null
        val now = to.int(name) ?: return null
        return (now - was).takeIf { it != 0 }
    }

    private fun swap(from: String, to: String, byEntity: Boolean): Hit? {
        val stage = STAGES.indexOfFirst { from.startsWith(it) && it.isNotEmpty() }.coerceAtLeast(0)
        val stageTo = STAGES.indexOfFirst { to.startsWith(it) && it.isNotEmpty() }.coerceAtLeast(0)
        return when {
            from == "grass_block" && to == "dirt" -> Hit(if (byEntity) "ate" else "withered", false)
            from == "dirt" && to in setOf("grass_block", "mycelium", "podzol") -> Hit("spread", true)
            to in setOf("sculk", "sculk_vein") && from !in setOf("sculk", "sculk_vein") -> Hit("spread", true)
            from == "farmland" && to == "dirt" -> Hit("trampled", false)
            from == "coarse_dirt" && to == "dirt" -> Hit("tilled", false)
            to == "farmland" && from in TILLABLE -> Hit("tilled", false)
            to == "dirt_path" && from in TILLABLE -> Hit("flattened", false)
            from in GROWTH && to in GROWTH && GROWTH.indexOf(to) > GROWTH.indexOf(from) -> Hit("grew", true)
            to == "stripped_$from" -> Hit("stripped", false)
            to == "waxed_$from" -> Hit("waxed", false)
            from == "waxed_$to" -> Hit("unwaxed", true)
            from.removePrefix(STAGES[stage]) == to.removePrefix(STAGES[stageTo]) && stage != stageTo ->
                Hit(if (stageTo > stage) "oxidized" else "scraped", true)

            to == "dead_$from" -> Hit("withered", false)
            from.endsWith("_concrete_powder") && to == from.removeSuffix("_powder") -> Hit("hardened", true)
            (from == "water" || from == "lava") && to in COOLED -> Hit("cooled", true)
            from == "water" && to in ICE -> Hit("froze", true)
            from in ICE && to == "water" -> Hit("melted", false)
            from == "sponge" && to == "wet_sponge" -> Hit("soaked", true)
            from == "wet_sponge" && to == "sponge" -> Hit("dried", true)
            from == "pumpkin" && (to == "carved_pumpkin" || to == "jack_o_lantern") -> Hit("carved", false)
            else -> null
        }
    }
}
