package com.tracel.plugin.specifics.names

import org.bukkit.Material

/** Vanilla item names for tab completion. */
internal val VANILLA_ITEM_NAMES: List<String> by lazy { vanillaNames(items = true, blocks = false) }

/** Vanilla block names for tab completion. */
internal val VANILLA_BLOCK_NAMES: List<String> by lazy { vanillaNames(items = false, blocks = true) }

private fun vanillaNames(items: Boolean, blocks: Boolean): List<String> =
    Material.entries
        .asSequence()
        .filter { !it.isLegacy }
        .filter { (items && it.isItem) || (blocks && it.isBlock) }
        .map { it.name.lowercase() }
        .toList()
