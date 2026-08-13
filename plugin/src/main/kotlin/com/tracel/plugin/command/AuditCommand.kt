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

/**
 * `/tracel audit players` and `/tracel audit block <x> <y> <z> [world]` — the drift oracle:
 * scans live `Bukkit` state for a holder and compares it against what the ledger believes that
 * holder has.
 */
class AuditCommand(private val services: TracelServices) : BasicCommand {
    override fun execute(source: CommandSourceStack, args: Array<String>) {
        when (args.getOrNull(1)) {
            "players" -> auditPlayers(source.sender)
            "block" -> auditBlock(source.sender, args)
            else -> source.sender.sendMessage(
                "Usage: /tracel audit players | /tracel audit block <x> <y> <z> [world]."
            )
        }
    }

    private fun auditPlayers(sender: CommandSender) {
        for (player in Bukkit.getOnlinePlayers()) {
            val holder = HolderId.Player(player.uniqueId)

            services.scope.launch {
                val live = withContext(services.schedulers.entity(player.uniqueId)) {
                    player.inventory.toItemTotals().withCursor(player)
                }
                val believed = withContext(services.schedulers.storage) { services.ledger.totalsAt(holder) }
                report(sender, "player ${player.name}.", diffTotals(live, believed))
            }
        }
    }

    private fun auditBlock(sender: CommandSender, args: Array<String>) {
        val x = args.getOrNull(2)?.toIntOrNull()
        val y = args.getOrNull(3)?.toIntOrNull()
        val z = args.getOrNull(4)?.toIntOrNull()
        val world = args.getOrNull(5)?.let(Bukkit::getWorld) ?: (sender as? org.bukkit.entity.Player)?.world

        if (x == null || y == null || z == null || world == null) {
            sender.sendMessage("Usage: /tracel audit block <x> <y> <z> [world].")
            return
        }

        val holder = HolderId.Block(WorldId(world.uid), x, y, z)

        services.scope.launch {
            val live = withContext(services.schedulers.region(holder)) {
                val state = world.getBlockAt(x, y, z).state
                if (state !is Container) {
                    null
                } else {
                    state.inventory.toItemTotals()
                }
            }

            if (live == null) {
                sender.sendMessage("Block at $x,$y,$z in ${world.name} is not a container.")
                return@launch
            }

            val believed = withContext(services.schedulers.storage) { services.ledger.totalsAt(holder) }
            report(sender, "block $x,$y,$z", diffTotals(live, believed))
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

    override fun permission(): String = "tracel.audit"
}
