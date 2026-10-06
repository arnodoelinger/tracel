package com.tracel.plugin.command.args.rollback

import com.tracel.engine.log.lookup.LookupFilter
import com.tracel.model.holder.HolderId
import com.tracel.plugin.adapter.command.resolvePlayerUuid
import com.tracel.plugin.adapter.world.toLookupRegion
import com.tracel.plugin.adapter.world.toWorldId
import com.tracel.plugin.command.args.action.ActionArgument
import com.tracel.plugin.command.args.lookup.ParsedLookupArgs
import com.tracel.plugin.command.args.scope.ScopeLimits
import com.tracel.plugin.command.args.scope.actionProblems
import com.tracel.plugin.command.args.scope.scopeProblem
import com.tracel.plugin.command.args.support.MaterialAliases
import com.tracel.plugin.i18n.tr
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import java.util.*

object RollbackArgument {
    fun build(
        sender: CommandSender,
        parsed: ParsedLookupArgs,
        limit: Int,
    ): FilterResult {
        val reasons = mutableListOf<Component>()

        val users = resolvePlayers(parsed.users, reasons)

        val actions = ActionArgument.parse(parsed.actions)
        reasons += actionProblems(actions, parsed.actions)

        val namedWorld = parsed.world?.let(Bukkit::getWorld)
        if (parsed.world != null && namedWorld == null) {
            reasons += tr("common.reason.unknown_world", "name" to parsed.world)
        }

        val scope = parsed.scope
        val center = if (scope != null) (sender as? Player)?.location else null
        scopeProblem(scope, center, namedWorld, ScopeLimits.rollbackMaxBlocks)?.let { reasons += it }

        reasons += missingBounds(parsed)
        if (parsed.since != null && parsed.until != null && parsed.since > parsed.until) {
            reasons += tr("common.reason.no_window")
        }

        if (reasons.isNotEmpty()) {
            return FilterResult.Rejected(reasons)
        }

        return FilterResult.Ok(
            filter = LookupFilter(
                holders = users.mapTo(hashSetOf(), HolderId::Player),
                material = parsed.item?.let { MaterialAliases.resolve(it).first },
                blockMaterials = parsed.item?.let { MaterialAliases.resolve(it).second }.orEmpty(),
                causes = actions.causes,
                worldCauses = actions.worldCauses,
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

    /** A rollback with no time or no place undoes the history of the server, so it is refused. */
    internal fun missingBounds(parsed: ParsedLookupArgs): List<Component> = buildList {
        if (parsed.since == null) add(tr("common.reason.need_time"))
        if (parsed.scope == null) add(tr("common.reason.need_scope"))
    }

    private fun resolvePlayers(names: Set<String>, reasons: MutableList<Component>): List<UUID> =
        names.mapNotNull { name ->
            resolvePlayerUuid(name) ?: run {
                reasons += tr("common.reason.unknown_player", "name" to name)
                null
            }
        }
}
