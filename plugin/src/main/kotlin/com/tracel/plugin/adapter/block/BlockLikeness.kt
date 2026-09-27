package com.tracel.plugin.adapter.block

import com.tracel.annotations.Unstable
import java.util.concurrent.ConcurrentHashMap

// TODO: rewrite

/**
 * Decides whether a block changed by vanilla ticking can still be treated as the expected block.
 *
 * Used by rollback validation to avoid treating things like crop growth, snow, and copper oxidation
 * as player changes.
 */
@Unstable
internal object BlockLikeness {
    private val materials = ConcurrentHashMap<String, String>()
    private val stripped = ConcurrentHashMap<String, String>()
    private val families = ConcurrentHashMap<String, String>()

    private val COSMETIC = setOf(
        "snowy", "age", "moisture", "power", "powered", "lit", "unstable", "triggered",
        "occupied", "berries", "distance", "persistent", "bloom", "can_summon", "shrieking",
        "hatch", "eggs", "dusted", "delay", "locked", "level",
    )

    private val GROUND = setOf(
        "dirt", "grass_block", "mycelium", "podzol", "dirt_path", "coarse_dirt", "rooted_dirt",
    )

    private val COPPER_WEATHER = listOf("exposed_", "weathered_", "oxidized_")

    private data class Parsed(val material: String, val props: Map<String, String>)

    fun sameEnough(standing: String, expected: String): Boolean {
        if (standing == expected) return true
        val a = materialOf(standing)
        val b = materialOf(expected)
        if (a == b) return strippedOf(standing) == strippedOf(expected)
        val family = familyOf(a) ?: return false
        return family == familyOf(b)
    }

    private fun materialOf(raw: String): String = materials.getOrPut(raw) {
        val cut = raw.indexOf('[')
        if (cut < 0) raw else raw.substring(0, cut)
    }

    private fun strippedOf(raw: String): String = stripped.getOrPut(raw) { strip(parse(raw)) }

    private fun familyOf(material: String): String? =
        families.getOrPut(material) { family(material) ?: "" }.takeIf { it.isNotEmpty() }

    private fun strip(parsed: Parsed): String {
        val kept = parsed.props.filterKeys { it !in COSMETIC }
        if (kept.isEmpty()) return parsed.material
        return parsed.material + kept.entries.sortedBy { it.key }
            .joinToString(",", "[", "]") { "${it.key}=${it.value}" }
    }

    private fun family(material: String): String? {
        val name = material.removePrefix("minecraft:")
        if (name in GROUND) return "ground"
        copperCore(name)?.let { return "copper:$it" }
        return null
    }

    private fun copperCore(name: String): String? {
        var s = name
        if (s.startsWith("waxed_")) s = s.removePrefix("waxed_")
        for (prefix in COPPER_WEATHER) {
            if (s.startsWith(prefix)) s = s.removePrefix(prefix)
        }
        return s.takeIf { "copper" in it && "ore" !in it && s != "raw_copper_block" }
    }

    private fun parse(raw: String): Parsed {
        val cut = raw.indexOf('[')
        if (cut < 0) return Parsed(raw, emptyMap())
        val material = raw.substring(0, cut)
        val end = if (raw.endsWith(']')) raw.length - 1 else raw.length
        val inner = raw.substring(cut + 1, end)
        val props = inner.split(',').mapNotNull { part ->
            val eq = part.indexOf('=')
            if (eq < 0) null else part.substring(0, eq) to part.substring(eq + 1)
        }.toMap()
        return Parsed(material, props)
    }
}
