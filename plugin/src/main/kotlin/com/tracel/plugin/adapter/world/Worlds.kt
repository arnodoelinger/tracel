package com.tracel.plugin.adapter.world

import com.tracel.model.id.WorldId
import java.util.UUID
import org.bukkit.Bukkit
import org.bukkit.World
import org.bukkit.entity.Player

/** The live world for [id], or null if it is not loaded. */
fun worldOf(id: WorldId): World? = Bukkit.getWorld(id.uuid)

/** The live world for [uuid], or null if it is not loaded. */
fun worldOf(uuid: UUID): World? = Bukkit.getWorld(uuid)

/** Whether this player is on the server right now. */
fun playerIsOnline(uuid: UUID): Boolean = Bukkit.getPlayer(uuid) != null

/** The live player, or `null` if they are offline. */
fun playerOf(uuid: UUID): Player? = Bukkit.getPlayer(uuid)
