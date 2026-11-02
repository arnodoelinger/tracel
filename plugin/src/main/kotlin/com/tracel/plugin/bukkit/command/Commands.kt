package com.tracel.plugin.bukkit.command

import com.tracel.plugin.util.say
import com.tracel.plugin.util.CommandOrigin
import com.tracel.plugin.util.ParsedBlockPos
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.util.Vector
import java.util.*

/** This block coordinate as a `Bukkit` vector. */
internal fun ParsedBlockPos.vector(): Vector = Vector(x.toDouble(), y.toDouble(), z.toDouble())

/** Converts the player's current location into a [CommandOrigin]. */
internal fun Player.toCommandOrigin(): CommandOrigin {
    val loc = location
    return CommandOrigin(loc.x, loc.y, loc.z)
}

/** @return the sender as a [Player], or sends [message] and returns `null`. */
fun CommandSender.requirePlayer(message: Component): Player? {
    val player = this as? Player
    if (player == null) say(message)
    return player
}

/**
 * Resolves an offline or online player name to their UUID.
 *
 * @return `null` if no record of a player by that name exists.
 */
fun resolvePlayerUuid(name: String): UUID? {
    if (name.isBlank()) return null
    Bukkit.getPlayerExact(name)?.let { return it.uniqueId }
    val offline = Bukkit.getOfflinePlayerIfCached(name) ?: return null
    return if (offline.hasPlayedBefore()) offline.uniqueId else null
}
