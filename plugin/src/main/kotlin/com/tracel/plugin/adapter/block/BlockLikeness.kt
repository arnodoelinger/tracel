package com.tracel.plugin.adapter.block

import com.tracel.annotations.Unstable
import com.tracel.plugin.specifics.block.COSMETIC_PROPERTIES
import com.tracel.plugin.specifics.block.GroundBlock
import com.tracel.plugin.specifics.block.copperCore
import com.tracel.plugin.specifics.command.VANILLA_NAMESPACE
import java.util.concurrent.ConcurrentHashMap

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
        val kept = parsed.props.filterKeys { it !in COSMETIC_PROPERTIES }
        if (kept.isEmpty()) return parsed.material
        return parsed.material + kept.entries.sortedBy { it.key }
            .joinToString(",", "[", "]") { "${it.key}=${it.value}" }
    }

    private fun family(material: String): String? {
        val name = material.removePrefix(VANILLA_NAMESPACE)
        if (name in GroundBlock.ids) return "ground"
        copperCore(name)?.let { return "copper:$it" }
        return null
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
