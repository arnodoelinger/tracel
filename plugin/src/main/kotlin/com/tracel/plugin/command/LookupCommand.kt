package com.tracel.plugin.command

import com.tracel.annotations.CauseKind
import com.tracel.engine.log.LookupFilter
import com.tracel.model.holder.HolderId
import com.tracel.model.transaction.Transaction
import com.tracel.plugin.TracelServices
import com.tracel.plugin.lookup.LookupScope
import com.tracel.plugin.lookup.ParsedLookupArgs
import com.tracel.plugin.lookup.countUnits
import com.tracel.plugin.lookup.parseLookupArgs
import com.tracel.plugin.lookup.renderLookupResult
import com.tracel.plugin.lookup.suggestLookupToken
import io.papermc.paper.command.brigadier.BasicCommand
import io.papermc.paper.command.brigadier.CommandSourceStack
import kotlinx.coroutines.launch
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import kotlin.math.abs

/**
 * `/tracel lookup user:<name> item:<material> action:<cause> time:<2h|today|yesterday|2026-08-20|from..to>
 * scope:<n|Nc|chunk|world> -user:<name> #count #count-only [limit:<n>] [offset:<n>]` query over the
 * append-only [TransactionLog][com.tracel.engine.log.TransactionLog], filtered by [LookupFilter].
 */
class LookupCommand(private val services: TracelServices) : BasicCommand {
    override fun execute(source: CommandSourceStack, args: Array<String>) {
        val sender = source.sender
        val parsed = parseLookupArgs(args.drop(1), System.currentTimeMillis())

        if (parsed.errors.isNotEmpty()) {
            sender.sendMessage("Lookup errors: ${parsed.errors.joinToString(", ")}")
            sender.sendMessage(USAGE)
            return
        }

        val unresolvedUsers = parsed.users.filter { resolvePlayer(it) == null }
        if (unresolvedUsers.isNotEmpty()) {
            sender.sendMessage("Unknown player(s): ${unresolvedUsers.joinToString(", ")}")
            return
        }
        val users = parsed.users.mapNotNull { resolvePlayer(it) }
        val excludedUsers = parsed.excludedUsers.mapNotNull { resolvePlayer(it) }

        val causes = parsed.actions.mapNotNull { name ->
            runCatching { CauseKind.valueOf(name.uppercase()) }.getOrNull()
        }
        val badCauses = parsed.actions.filter { name -> runCatching { CauseKind.valueOf(name.uppercase()) }.isFailure }
        if (badCauses.isNotEmpty()) {
            sender.sendMessage("Unknown action(s): ${badCauses.joinToString(", ")} — known: ${CauseKind.entries.joinToString(",") { it.name.lowercase() }}")
            return
        }

        val filter = LookupFilter(
            holders = users.map(HolderId::Player).toSet(),
            excludedHolders = excludedUsers.map(HolderId::Player).toSet(),
            material = parsed.item,
            causes = causes.toSet(),
            since = parsed.since,
            until = parsed.until,
            limit = parsed.limit ?: 100,
            offset = parsed.offset,
        )

        val scope = parsed.scope
        if (scope is LookupScope.World && Bukkit.getWorld(scope.name) == null) {
            sender.sendMessage("Unknown world: ${scope.name}")
            return
        }
        val center = if (scope != null && scope !is LookupScope.World) (sender as? Player)?.location else null
        if (scope != null && scope !is LookupScope.World && center == null) {
            sender.sendMessage("scope:${describeScope(scope)} needs a player location — run this as a player, or use scope:<world> for a whole-world search.")
            return
        }

        runPage(sender, filter, parsed, center)
    }

    private fun runPage(sender: CommandSender, filter: LookupFilter, parsed: ParsedLookupArgs, center: Location?) {
        services.scope.launch {
            val results = services.atomically { services.log.query(filter) }
            val scope = parsed.scope
            val spatial = if (scope != null) {
                results.filter { matchesScope(it, scope, center, parsed.horizontalOnly) }
            } else {
                results
            }

            if (parsed.countOnly || parsed.count) {
                sender.sendMessage("${spatial.size} transaction(s) on this page, ${countUnits(spatial, parsed.item)} unit(s).")
                if (parsed.countOnly) return@launch
            }

            if (spatial.isEmpty()) {
                sender.sendMessage("No matches.")
                return@launch
            }

            sender.sendMessage(lookupHeadline(parsed))
            for (txn in spatial) {
                renderLookupResult(txn, parsed.item).forEach(sender::sendMessage)
            }

            if (scope == null && results.size == filter.limit) {
                sender.sendMessage("More results may exist — rerun with offset:${filter.offset + filter.limit} to see the next page.")
            }
        }
    }

    private fun lookupHeadline(parsed: ParsedLookupArgs): String = buildString {
        append("Search results")
        val filters = buildList {
            if (parsed.users.isNotEmpty()) add("player ${parsed.users.joinToString(", ")}")
            parsed.item?.let { add("item $it") }
            if (parsed.actions.isNotEmpty()) add("action ${parsed.actions.joinToString(", ")}")
            if (parsed.since != null || parsed.until != null) add("time filter active")
            parsed.scope?.let { add("scope ${describeScope(it)}") }
        }
        if (filters.isNotEmpty()) {
            append(" (")
            append(filters.joinToString(" · "))
            append(')')
        }
    }

    private fun resolvePlayer(name: String): java.util.UUID? =
        Bukkit.getOfflinePlayer(name).takeIf { it.hasPlayedBefore() || it.isOnline }?.uniqueId

    private fun describeScope(scope: LookupScope): String = when (scope) {
        is LookupScope.Blocks -> "${scope.radius}"
        is LookupScope.Chunks -> "${scope.radius}c"
        LookupScope.CurrentChunk -> "chunk"
        is LookupScope.World -> scope.name
    }

    private fun matchesScope(txn: Transaction, scope: LookupScope, center: Location?, horizontalOnly: Boolean): Boolean {
        val touched = txn.flows.flatMap { listOf(it.source, it.destination) } + listOfNotNull(txn.causedBy)
        return when (scope) {
            is LookupScope.World -> {
                val worldUid = Bukkit.getWorld(scope.name)?.uid ?: return false
                touched.any { spatialOf(it)?.worldUid == worldUid }
            }
            is LookupScope.Blocks -> center != null && touched.any { holder ->
                val (worldUid, x, y, z) = spatialOf(holder) ?: return@any false
                if (worldUid != center.world?.uid) return@any false
                val dx = x - center.blockX
                val dz = z - center.blockZ
                val dy = y - center.blockY
                if (horizontalOnly) dx * dx + dz * dz <= scope.radius * scope.radius else dx * dx + dy * dy + dz * dz <= scope.radius * scope.radius
            }
            is LookupScope.Chunks -> center != null && touched.any { holder ->
                val (worldUid, x, _, z) = spatialOf(holder) ?: return@any false
                if (worldUid != center.world?.uid) return@any false
                val dcx = (x shr 4) - (center.blockX shr 4)
                val dcz = (z shr 4) - (center.blockZ shr 4)
                maxOf(abs(dcx), abs(dcz)) <= scope.radius
            }
            LookupScope.CurrentChunk -> center != null && touched.any { holder ->
                val (worldUid, x, _, z) = spatialOf(holder) ?: return@any false
                worldUid == center.world?.uid && (x shr 4) == (center.blockX shr 4) && (z shr 4) == (center.blockZ shr 4)
            }
        }
    }

    private fun spatialOf(holder: HolderId): SpatialHolder? = when (holder) {
        is HolderId.Block -> SpatialHolder(holder.world.uuid, holder.x, holder.y, holder.z)
        is HolderId.PlacedBlock -> SpatialHolder(holder.world.uuid, holder.x, holder.y, holder.z)
        else -> null
    }

    private data class SpatialHolder(val worldUid: java.util.UUID, val x: Int, val y: Int, val z: Int)

    override fun suggest(source: CommandSourceStack, args: Array<String>): Collection<String> =
        suggestLookupToken(
            partial = args.lastOrNull() ?: "",
            onlinePlayerNames = Bukkit.getOnlinePlayers().map { it.name },
            worldNames = Bukkit.getWorlds().map { it.name },
            causeNames = CauseKind.entries.map { it.name.lowercase() },
        )

    override fun permission(): String = "tracel.lookup"

    private companion object {
        const val USAGE = "Usage: /tracel lookup user:<name> item:<material> action:<cause> " +
            "time:<2h|today|yesterday|2026-08-20|from..to> scope:<n|Nc|chunk|world> -user:<name> " +
            "#count #count-only [limit:<n>] [offset:<n>]"
    }
}
