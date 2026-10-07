package com.tracel.plugin.integration.update

import com.tracel.annotations.Observes
import com.tracel.plugin.command.permission.Permission
import com.tracel.plugin.command.permission.has
import com.tracel.plugin.i18n.say
import com.tracel.plugin.i18n.tr
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.JoinConfiguration
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.plugin.Plugin

/** How long to wait before telling a joining operator, so it is not lost in the join spam. */
private const val SHOW_AFTER_TICKS = 40L

/** Tells operators who join that a newer `Tracel` is out. */
internal class UpdateListener(private val checker: UpdateChecker, private val plugin: Plugin) : Listener {
    /** Shows the notice to whoever may see the status panel. */
    @Observes
    fun onJoin(event: PlayerJoinEvent) {
        val player = event.player
        if (checker.available == null || !player.has(Permission.STATUS)) return
        player.scheduler.runDelayed(plugin, { _ ->
            val release = checker.available ?: return@runDelayed
            player.say(
                Component.join(
                    JoinConfiguration.newlines(),
                    tr("update.title", "version" to release.version),
                    Component.empty(),
                    tr("update.ran", "version" to checker.running),
                    tr("update.advice"),
                    Component.empty(),
                    Component.join(
                        JoinConfiguration.separator(Component.text("  ")),
                        tr("update.download")
                            .hoverEvent(HoverEvent.showText(tr("update.download_hover")))
                            .clickEvent(ClickEvent.openUrl(release.download)),
                        tr("update.news")
                            .hoverEvent(HoverEvent.showText(tr("update.news_hover")))
                            .clickEvent(ClickEvent.openUrl(release.page)),
                    ),
                )
            )
        }, null, SHOW_AFTER_TICKS)
    }
}
