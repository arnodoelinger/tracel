package com.tracel.plugin.util

import org.bukkit.Material

/**
 * The materials of these [names] that this server has. A block added in a newer Minecraft is simply missing on an
 * older one, so a list that names it can be written once for all of them.
 */
internal fun materialsNamed(vararg names: String): Set<Material> =
    names.mapNotNullTo(HashSet()) { Material.matchMaterial(it) }
