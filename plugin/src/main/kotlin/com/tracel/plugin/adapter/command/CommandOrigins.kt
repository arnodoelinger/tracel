package com.tracel.plugin.adapter.command

import com.tracel.plugin.util.command.CommandOrigin
import org.bukkit.entity.Player

/** Converts the player's current location into a [CommandOrigin]. */
internal fun Player.toCommandOrigin(): CommandOrigin {
    val loc = location
    return CommandOrigin(loc.x, loc.y, loc.z)
}
