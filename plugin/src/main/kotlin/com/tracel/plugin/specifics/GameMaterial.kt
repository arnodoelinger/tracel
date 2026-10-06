package com.tracel.plugin.specifics

import org.bukkit.Material
import java.util.*

/**
 * One line of a game list.
 *
 * Every list in `specifics` is an enum of these, so a new Minecraft version is one more line.
 */
internal interface GameMaterial {
    /** The material this line names, or `null` when this server's Minecraft does not have it. */
    val material: Material?
}

/**
 * The material called [name], or `null` on a server that does not have it.
 *
 * For a block newer than the oldest supported version: the list names it once, and an older server simply
 * goes without.
 */
internal fun materialNamed(name: String): Material? = Material.matchMaterial(name)

/** The materials of [entries] that this server has. */
internal fun materialsOf(entries: List<GameMaterial>): Set<Material> {
    val found = EnumSet.noneOf(Material::class.java)
    for (entry in entries) entry.material?.let(found::add)
    return found
}
