package com.tracel.plugin.util.command

/**
 * Where `~` resolves.
 *
 * `null` sender origin (console / RCON) may only use absolute coordinates.
 */
internal data class CommandOrigin(val x: Double, val y: Double, val z: Double)
