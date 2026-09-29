package com.tracel.plugin.command.who

import com.tracel.engine.log.LookupFilter
import com.tracel.model.holder.HolderId
import com.tracel.plugin.TracelServices
import com.tracel.plugin.command.args.ParsedLookupArgs
import com.tracel.plugin.command.args.ScopeArgument
import com.tracel.plugin.command.args.ScopeLimits
import com.tracel.plugin.command.args.ScopeLimits.isOversized
import com.tracel.plugin.util.resolvePlayerUuid
import com.tracel.plugin.util.toLookupRegion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

// TODO: rewrite

/** `/tracel who <player>`: what a player did, on one screen. */
internal class WhoAction(private val services: TracelServices) {
    fun execute(sender: CommandSender, name: String, parsed: ParsedLookupArgs) {
        if (parsed.errors.isNotEmpty()) {
            parsed.errors.forEach { sender.sendMessage("Who: $it") }
            return
        }
        val uuid = resolvePlayerUuid(name)
        if (uuid == null) {
            sender.sendMessage("Unknown player: $name")
            return
        }
        val now = System.currentTimeMillis()
        val since = parsed.since ?: (now - DEFAULT_WINDOW)
        val scope = parsed.scope
        val center = if (scope != null) (sender as? Player)?.location else null
        if (scope != null && center == null) {
            sender.sendMessage("Who: scope:${ScopeArgument.describe(scope)} needs a player location — run this as a player.")
            return
        }
        if (scope != null && scope.isOversized()) {
            sender.sendMessage("Who: radius is too large (max ${ScopeLimits.MAX_BLOCK_RADIUS} blocks).")
            return
        }
        val filter = LookupFilter(
            holders = setOf(HolderId.Player(uuid)),
            since = since,
            until = parsed.until,
            region = center?.toLookupRegion(scope, parsed.horizontalOnly),
            limit = READ_LIMIT,
        )
        services.scope.launch {
            services.flushCapture()
            val (txns, changes) = try {
                coroutineScope {
                    val t = async { services.reading { services.log.query(filter) } }
                    val w = async { services.reading { services.worldLog.query(filter) } }
                    t.await() to w.await()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                sender.sendMessage("Who failed: ${failure.message ?: failure::class.java.simpleName}")
                return@launch
            }
            val report = WhoReport.of(uuid, changes, txns)
            val truncated = txns.size >= READ_LIMIT || changes.size >= READ_LIMIT
            render(sender, name, now - since, report, truncated, scope?.let(ScopeArgument::describe))
        }
    }

    private fun render(
        sender: CommandSender,
        name: String,
        window: Long,
        r: WhoReport,
        truncated: Boolean,
        scope: String?,
    ) {
        val span = shortSpan(window)
        if (r.records == 0) {
            sender.sendMessage("$name — nothing recorded in the last $span${scope?.let { " within $it" } ?: ""}.")
            return
        }
        sender.sendMessage("$name — last $span${scope?.let { ", within $it" } ?: ""} · ${r.records} records")
        if (r.placed + r.broken + r.changed + r.signs > 0) {
            sender.sendMessage(
                "  Blocks   +${r.placed} placed  −${r.broken} broken  ~${r.changed} changed" +
                        (if (r.signs > 0) "  ${r.signs} signs" else "") + top(r.topPlaced, r.topBroken)
            )
        }
        if (r.took + r.stored + r.dropped + r.pickedUp > 0) {
            sender.sendMessage(
                "  Items    took ${r.took} · stored ${r.stored} · dropped ${r.dropped} · picked up ${r.pickedUp}" +
                        if (r.topItems.isEmpty()) "" else "  (${r.topItems.joinToString(", ") { "${it.first} ${it.second}" }})"
            )
        }
        if (r.kills.isNotEmpty()) sender.sendMessage("  Kills    ${r.kills.joinToString(", ") { "${it.first} ${it.second}" }}")
        val now = System.currentTimeMillis()
        val spot = r.hotspot
        val seen = "  Seen     first ${shortSpan(now - (r.firstAt ?: now))} ago · last ${shortSpan(now - (r.lastAt ?: now))} ago"
        if (spot == null) sender.sendMessage(seen) else {
            sender.sendMessage(
                Component.text("$seen · busiest around ${spot.x}, ${spot.y}, ${spot.z} ")
                    .append(
                        Component.text("[tp]", NamedTextColor.AQUA)
                            .clickEvent(ClickEvent.suggestCommand("/tp ${spot.x} ${spot.y} ${spot.z}"))
                            .hoverEvent(HoverEvent.showText(Component.text("Put /tp ${spot.x} ${spot.y} ${spot.z} in the chat box")))
                    )
            )
        }
        if (truncated) sender.sendMessage("  Only the newest $READ_LIMIT of each log were counted — narrow it with a shorter time.")
        sender.sendMessage(
            Component.text("  ", NamedTextColor.GRAY).append(
                Component.text("[Roll back ${name}]", NamedTextColor.AQUA)
                    .clickEvent(ClickEvent.suggestCommand("/tracel rollback u:$name t:${shortSpan(window)} scope:"))
                    .hoverEvent(HoverEvent.showText(Component.text("Start a rollback of $name — add a scope:")))
            )
        )
    }

    private fun top(placed: List<Pair<String, Int>>, broken: List<Pair<String, Int>>): String {
        val parts = buildList {
            if (placed.isNotEmpty()) add("placed ${placed.joinToString(", ") { "${it.first} ${it.second}" }}")
            if (broken.isNotEmpty()) add("broke ${broken.joinToString(", ") { "${it.first} ${it.second}" }}")
        }
        return if (parts.isEmpty()) "" else "  (${parts.joinToString("; ")})"
    }

    private companion object {
        const val DEFAULT_WINDOW = 24L * 3_600_000
        const val READ_LIMIT = 5_000
    }
}

/** `90s`, `12m`, `3h`, `2d`: the biggest unit that is still at least one. */
internal fun shortSpan(millis: Long): String {
    val s = millis / 1_000
    return when {
        s < 60 -> "${maxOf(s, 1)}s"
        s < 3_600 -> "${s / 60}m"
        s < 86_400 -> "${s / 3_600}h"
        else -> "${s / 86_400}d"
    }
}
