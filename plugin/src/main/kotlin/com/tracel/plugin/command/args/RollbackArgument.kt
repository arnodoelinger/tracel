package com.tracel.plugin.command.args

import com.tracel.engine.log.LookupFilter
import com.tracel.model.holder.HolderId
import com.tracel.plugin.command.args.ScopeLimits.isOversized
import com.tracel.plugin.util.resolvePlayerUuid
import com.tracel.plugin.util.toLookupRegion
import com.tracel.plugin.util.toWorldId
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import java.util.UUID

sealed interface FilterResult {
    data class Ok(
        val filter: LookupFilter,
        val center: Location?,
        val actions: ActionFilter,
    ) : FilterResult

    data class Rejected(val reasons: List<String>) : FilterResult
}

object RollbackArgument {
    fun build(
        sender: CommandSender,
        parsed: ParsedLookupArgs,
        limit: Int,
    ): FilterResult {
        val reasons = mutableListOf<String>()

        val users = resolvePlayers(parsed.users, "unknown player", reasons)
        val excluded = resolvePlayers(parsed.excludedUsers, "unknown player to exclude", reasons)

        val actions = ActionArgument.parse(parsed.actions)
        actions.unknown.forEach { reasons += "unknown action: $it" }

        val namedWorld = parsed.world?.let(Bukkit::getWorld)
        if (parsed.world != null && namedWorld == null) {
            reasons += "unknown world: ${parsed.world}"
        }

        val scope = parsed.scope
        val center = if (scope != null) (sender as? Player)?.location else null

        when {
            scope != null && center == null ->
                reasons += "a radius needs a player location — run this as a player, or name a world with w:"

            scope != null && namedWorld != null && center != null && center.world?.uid != namedWorld.uid ->
                reasons += "scope:${ScopeArgument.describe(scope)} is a cube around you and you are not in " +
                        "${namedWorld.name} — drop the w:, or run it from there"

            scope?.isOversized() == true ->
                reasons += "radius is too large (max ${ScopeLimits.MAX_BLOCK_RADIUS} blocks / " +
                        "${ScopeLimits.MAX_CHUNK_RADIUS} chunks) — use scope:${ScopeLimits.MAX_BLOCK_RADIUS}b " +
                        "or scope:${ScopeLimits.MAX_CHUNK_RADIUS}c"
        }

        if (parsed.since == null && parsed.until == null && parsed.lot == null) {
            reasons += "give a time bound (t:1h) — an unbounded rollback undoes the whole history of the server"
        }

        if (reasons.isNotEmpty()) {
            return FilterResult.Rejected(reasons)
        }

        return FilterResult.Ok(
            filter = LookupFilter(
                holders = users.mapTo(hashSetOf(), HolderId::Player),
                excludedHolders = excluded.mapTo(hashSetOf(), HolderId::Player),
                material = parsed.item,
                causes = actions.causes,
                actions = actions.actions,
                since = parsed.since,
                until = parsed.until,
                region = center?.toLookupRegion(scope, parsed.horizontalOnly),
                world = namedWorld.toWorldId(),
                limit = limit,
            ),
            center = center,
            actions = actions,
        )
    }

    private fun resolvePlayers(
        names: Set<String>,
        unknownPrefix: String,
        reasons: MutableList<String>,
    ): List<UUID> = names.mapNotNull { name ->
        resolvePlayerUuid(name) ?: run {
            reasons += "$unknownPrefix: $name"
            null
        }
    }
}
