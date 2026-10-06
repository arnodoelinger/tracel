package com.tracel.plugin.command.action

import com.tracel.engine.log.lookup.LookupFilter
import com.tracel.model.holder.HolderId
import com.tracel.plugin.adapter.command.resolvePlayerUuid
import com.tracel.plugin.adapter.world.toLookupRegion
import com.tracel.plugin.command.args.lookup.ParsedLookupArgs
import com.tracel.plugin.command.args.scope.ScopeArgument
import com.tracel.plugin.command.args.scope.scopeProblem
import com.tracel.plugin.command.presenter.ItemPresenter
import com.tracel.plugin.command.presenter.PlayerReport
import com.tracel.plugin.i18n.*
import com.tracel.plugin.services.TracelServices
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.JoinConfiguration
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

/** `/tracel player <name>`: what a player did, on one screen. */
internal class PlayerAction(private val services: TracelServices) {
    private companion object {
        const val DEFAULT_WINDOW = 24L * 3_600_000
    }

    fun execute(sender: CommandSender, name: String, parsed: ParsedLookupArgs) {
        if (parsed.errors.isNotEmpty()) {
            refuse(sender, parsed.errors.asReason())
            return
        }
        val uuid = resolvePlayerUuid(name)
        if (uuid == null) {
            refuse(sender, tr("common.reason.unknown_player", "name" to name))
            return
        }
        val now = System.currentTimeMillis()
        val since = parsed.since ?: (now - DEFAULT_WINDOW)
        val scope = parsed.scope
        val center = if (scope != null) (sender as? Player)?.location else null
        scopeProblem(scope, center, null)?.let {
            refuse(sender, it)
            return
        }
        val filter = LookupFilter(
            holders = setOf(HolderId.Player(uuid)),
            since = since,
            until = parsed.until,
            region = center?.toLookupRegion(scope, parsed.horizontalOnly),
            limit = Int.MAX_VALUE,
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
                sender.failed("player.failed", Component.text(unexpected(failure)), tr("player.hint.again"))
                return@launch
            }
            val report = PlayerReport.of(uuid, changes, txns)
            render(sender, name, now - since, report, scope?.let(ScopeArgument::describe))
        }
    }

    private fun refuse(sender: CommandSender, reason: Component) =
        sender.failed("player.failed", reason, tr("common.hint.fix_flags", "command" to "player"))

    private fun render(
        sender: CommandSender,
        name: String,
        window: Long,
        r: PlayerReport,
        scope: String?,
    ) {
        val span = shortSpan(window)
        val head =
            mutableListOf(tr("player.title", "name" to name), Component.empty(), tr("player.window", "span" to span))
        scope?.let { head += tr("player.scope", "scope" to it) }
        if (r.records == 0) {
            head += info(tr("player.empty"))
            sender.say(Component.join(JoinConfiguration.newlines(), head))
            return
        }
        head += tr("player.records", "records" to r.records)
        val now = System.currentTimeMillis()
        head += tr(
            "player.active",
            "first" to shortSpan(now - (r.firstAt ?: now)),
            "last" to shortSpan(now - (r.lastAt ?: now))
        )
        r.hotspot?.let { spot -> head += tr("player.busiest", "x" to spot.x, "y" to spot.y, "z" to spot.z) }

        val lines = buildList {
            addAll(head)
            if (r.placed + r.broken + r.changed > 0) {
                add(Component.empty())
                add(
                    tr(
                        "player.blocks",
                        "plus" to ItemPresenter.PLUS, "placed" to r.placed,
                        "minus" to ItemPresenter.MINUS, "broken" to r.broken,
                        "both" to ItemPresenter.BOTH, "changed" to r.changed,
                    ),
                )
            }
        }.toMutableList()

        val buttons = buildList {
            r.hotspot?.let { spot ->
                add(
                    tr("player.button.tp")
                        .clickEvent(ClickEvent.suggestCommand("/tp ${spot.x} ${spot.y} ${spot.z}"))
                        .hoverEvent(
                            HoverEvent.showText(
                                tr(
                                    "player.button.tp_hover",
                                    "x" to spot.x,
                                    "y" to spot.y,
                                    "z" to spot.z
                                )
                            )
                        ),
                )
            }
            add(
                tr("player.button.lookup")
                    .clickEvent(ClickEvent.runCommand("/tracel lookup u:$name t:$span"))
                    .hoverEvent(HoverEvent.showText(tr("player.button.lookup_hover", "name" to name))),
            )
            add(
                tr("player.button.rollback")
                    .clickEvent(ClickEvent.suggestCommand("/tracel rollback u:$name t:$span scope:"))
                    .hoverEvent(HoverEvent.showText(tr("player.button.rollback_hover", "name" to name))),
            )
        }
        lines += Component.empty()
        lines += Component.join(JoinConfiguration.separator(Component.space()), buttons)
        sender.say(Component.join(JoinConfiguration.newlines(), lines))
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
