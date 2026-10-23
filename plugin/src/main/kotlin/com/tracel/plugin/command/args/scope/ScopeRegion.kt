package com.tracel.plugin.command.args.scope

import com.tracel.plugin.command.args.lookup.ParsedLookupArgs
import com.tracel.plugin.i18n.tr
import com.tracel.plugin.integration.worldguard.RegionBox
import com.tracel.plugin.integration.worldguard.Regions
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

/**
 * The `WorldGuard` region a scope word names: it is no world, but a region is called that,
 * the sender's world first.
 */
internal fun ParsedLookupArgs.region(sender: CommandSender): RegionBox? {
    val name = world ?: return null
    if (Bukkit.getWorld(name) != null) return null
    return Regions.find(name, (sender as? Player)?.world)
}

/**
 * Why [name] is no place: no world has it, and no region either when `WorldGuard` is there
 * to ask.
 */
internal fun unknownPlace(name: String): Component =
    tr(if (Regions.available) "common.reason.unknown_place" else "common.reason.unknown_world", "name" to name)
