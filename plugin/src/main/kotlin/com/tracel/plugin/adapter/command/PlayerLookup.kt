package com.tracel.plugin.adapter.command

import com.tracel.plugin.i18n.say
import java.util.*
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

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
