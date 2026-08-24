package com.tracel.plugin.command

import com.tracel.model.holder.HolderId
import com.tracel.model.id.WorldId
import com.tracel.plugin.TracelServices
import com.tracel.plugin.audit.Drift
import com.tracel.plugin.audit.diffTotals
import com.tracel.plugin.convert.toItemTotals
import com.tracel.plugin.convert.withCursor
import io.papermc.paper.command.brigadier.BasicCommand
import io.papermc.paper.command.brigadier.CommandSourceStack
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.bukkit.Bukkit
import org.bukkit.block.Container
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

/**
 * `/tracel audit players|block` scans live `Bukkit` state for a holder and
 * compares it against what the ledger believes that holder has.
 */
class AuditCommand(private val services: TracelServices) : BasicCommand {
    override fun execute(source: CommandSourceStack, args: Array<String>) {
        when (args.getOrNull(1)) {
            "players" -> players(source)
            "block" -> block(source, args)
            else -> usage(source)
        }
    }

    override fun suggest(source: CommandSourceStack, args: Array<String>): Collection<String> {
        if (args.size != 2) return emptyList()
        return listOf("players", "block").filter { it.startsWith(args[1]) }
    }

    override fun permission(): String = "tracel.audit"

    private fun usage(source: CommandSourceStack) {
        source.sender.sendMessage("Usage: /tracel audit players")
        source.sender.sendMessage("       /tracel audit block <x> <y> <z> [world]")
    }

    private fun players(source: CommandSourceStack) {
        for (player in Bukkit.getOnlinePlayers()) {
            val holder = HolderId.Player(player.uniqueId)

            services.scope.launch {
                val live = withContext(services.schedulers.entity(player.uniqueId)) {
                    player.inventory.toItemTotals().withCursor(player)
                }
                val believed = services.atomically { services.ledger.totalsAt(holder) }
                report(source.sender, "Player ${player.name}.", diffTotals(live, believed))
            }
        }
    }

    private fun block(source: CommandSourceStack, args: Array<String>) {
        val x = args.getOrNull(2)?.toIntOrNull()
        val y = args.getOrNull(3)?.toIntOrNull()
        val z = args.getOrNull(4)?.toIntOrNull()
        if (x == null || y == null || z == null) return usage(source)

        val resolvedWorld = args.getOrNull(5)?.let(Bukkit::getWorld) ?: (source.sender as? Player)?.world
        if (resolvedWorld == null) {
            source.sender.sendMessage("Name a world explicitly — the console isn't standing in one.")
            return
        }

        val holder = HolderId.Block(WorldId(resolvedWorld.uid), x, y, z)

        services.scope.launch {
            val live = withContext(services.schedulers.region(holder)) {
                val state = resolvedWorld.getBlockAt(x, y, z).state
                if (state !is Container) null else state.inventory.toItemTotals()
            }

            if (live == null) {
                source.sender.sendMessage("Block at $x $y $z in ${resolvedWorld.name} is not a container.")
                return@launch
            }

            val believed = services.atomically { services.ledger.totalsAt(holder) }
            report(source.sender, "block $x $y $z", diffTotals(live, believed))
        }
    }

    private fun report(sender: CommandSender, label: String, drifts: List<Drift>) {
        if (drifts.isEmpty()) {
            sender.sendMessage("$label: no drift.")
            return
        }
        sender.sendMessage("$label: ${drifts.size} drift(s)")
        for ((itemKey, live, believed) in drifts) {
            sender.sendMessage(" ${itemKey.material}: world has $live, ledger believes $believed.")
        }
    }
}
