package com.tracel.plugin.command.args.rollback

import com.tracel.plugin.adapter.command.resolvePlayerUuid
import com.tracel.plugin.command.args.action.ActionArgument
import com.tracel.plugin.command.args.lookup.ParsedLookupArgs
import com.tracel.plugin.command.args.lookup.partsLimitArea
import com.tracel.plugin.command.args.lookup.rollbackQuery
import com.tracel.plugin.config.ScopeLimits
import com.tracel.plugin.command.args.scope.areaOf
import com.tracel.plugin.command.args.scope.actionProblems
import com.tracel.plugin.command.args.scope.region
import com.tracel.plugin.command.args.scope.scopeProblem
import com.tracel.plugin.command.args.scope.unknownPlace
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
        wholeWorld: Boolean = false,
    ): FilterResult {
        val reasons = mutableListOf<Component>()

        val users = resolvePlayers(parsed.users, reasons)

        val actions = ActionArgument.parse(parsed.actions)
        reasons += actionProblems(actions, parsed.actions)

        val region = parsed.region(sender)
        val namedWorld = region?.world ?: parsed.world?.let(Bukkit::getWorld)
        if (parsed.world != null && namedWorld == null) {
            reasons += unknownPlace(parsed.world)
        }
        if (region != null && !region.cuboid && !region.whole) {
            reasons += tr("common.reason.region_shape", "name" to region.id)
        }

        val scope = parsed.scope
        val center = if (scope != null) (sender as? Player)?.location else null
        scopeProblem(scope, center, namedWorld, if (wholeWorld) null else ScopeLimits.rollbackMaxBlocks)?.let { reasons += it }

        reasons += missingBounds(parsed, wholeWorld, region != null)
        if (parsed.since != null && parsed.until != null && parsed.since > parsed.until) {
            reasons += tr("common.reason.no_window")
        }

        if (reasons.isNotEmpty()) {
            return FilterResult.Rejected(reasons)
        }

        return FilterResult.Ok(
            query = parsed.rollbackQuery(users, actions, region?.area ?: areaOf(center, scope, parsed.horizontalOnly, namedWorld)),
            center = center,
            actions = actions,
        )
    }

    /** A rollback with no time or no place undoes the history of the server, so it is refused. */
    internal fun missingBounds(
        parsed: ParsedLookupArgs,
        wholeWorld: Boolean = false,
        inRegion: Boolean = false,
    ): List<Component> = buildList {
        if (parsed.since == null) add(tr("common.reason.need_time"))
        val placed = inRegion || parsed.scope != null || parsed.partsLimitArea || (wholeWorld && parsed.world != null)
        if (!placed) add(tr("common.reason.need_scope"))
    }

    private fun resolvePlayers(names: Set<String>, reasons: MutableList<Component>): List<UUID> =
        names.mapNotNull { name ->
            resolvePlayerUuid(name) ?: run {
                reasons += tr("common.reason.unknown_player", "name" to name)
                null
            }
        }
}
