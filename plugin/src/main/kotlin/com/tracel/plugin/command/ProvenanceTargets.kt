package com.tracel.plugin.command

import com.tracel.model.holder.HolderId
import com.tracel.model.id.WorldId
import com.tracel.model.item.ItemKey
import com.tracel.plugin.TracelServices
import com.tracel.plugin.convert.toItemKey
import kotlinx.coroutines.withContext
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

fun provenanceUsage(sub: String): String =
    "Usage: /tracel $sub hand [player] | /tracel $sub block <x> <y> <z> <material> [world]"

/**
 * Resolves what `origin` / `trace` operate on.
 */
suspend fun resolveProvenanceTarget(services: TracelServices, sender: CommandSender, args: Array<String>): Pair<HolderId, ItemKey>? =
    when (args.getOrNull(1)) {
        "hand" -> {
            val player = args.getOrNull(2)?.let(Bukkit::getPlayer) ?: sender as? Player
            if (player == null) {
                null
            } else {
                val itemKey = withContext(services.schedulers.entity(player.uniqueId)) {
                    player.inventory.itemInMainHand.takeIf { !it.type.isAir }?.toItemKey()
                }
                itemKey?.let { HolderId.Player(player.uniqueId) to it }
            }
        }
        "block" -> {
            val x = args.getOrNull(2)?.toIntOrNull()
            val y = args.getOrNull(3)?.toIntOrNull()
            val z = args.getOrNull(4)?.toIntOrNull()
            val material = args.getOrNull(5)
            val world = args.getOrNull(6)?.let(Bukkit::getWorld) ?: (sender as? Player)?.world
            if (x == null || y == null || z == null || material == null || world == null) {
                null
            } else {
                HolderId.Block(WorldId(world.uid), x, y, z) to ItemKey(material)
            }
        }
        else -> null
    }

/** Tab-complete. */
fun suggestProvenanceTarget(sender: CommandSender, args: Array<String>): Collection<String> {
    val partial = args.lastOrNull() ?: ""
    val candidates = when (args.size) {
        2 -> listOf("hand", "block")
        3 -> if (args.getOrNull(1) == "hand") Bukkit.getOnlinePlayers().map { it.name } else emptyList()
        6 -> if (args.getOrNull(1) == "block") Material.entries.map { it.name } else emptyList()
        7 -> if (args.getOrNull(1) == "block") Bukkit.getWorlds().map { it.name } else emptyList()
        else -> emptyList()
    }
    return candidates.filter { it.startsWith(partial, ignoreCase = true) }
}
