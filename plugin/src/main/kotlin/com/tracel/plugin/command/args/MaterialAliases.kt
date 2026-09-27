package com.tracel.plugin.command.args

import org.bukkit.Material

/**
 * One name for both logs. The ledger knows items (`redstone`, `carrot`, `torch`), the world log knows the
 * blocks they stand as (`redstone_wire`, `carrots`, `wall_torch`); a filter naming either has to reach both,
 * or a rollback takes the item back and leaves the block standing.
 */
internal object MaterialAliases {
    private val blocksByItem: Map<Material, Set<String>> by lazy {
        val out = HashMap<Material, MutableSet<String>>()
        for (block in Material.entries) {
            if (block.isLegacy || !block.isBlock) continue
            val item = runCatching { block.createBlockData().placementMaterial }.getOrNull() ?: continue
            out.getOrPut(item) { HashSet() } += block.key.toString()
        }
        out
    }

    /** @return the item name for the ledger and every block id it stands as, for the world log. */
    fun resolve(name: String): Pair<String, Set<String>> {
        val material = Material.matchMaterial(name) ?: return name to emptySet()
        val item = when {
            material.isItem -> material
            else -> runCatching { material.createBlockData().placementMaterial }.getOrNull()?.takeIf { it.isItem }
                ?: material
        }
        val blocks =
            blocksByItem[item].orEmpty() + (if (material.isBlock) setOf(material.key.toString()) else emptySet())
        return item.name to blocks
    }
}
