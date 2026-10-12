package com.tracel.plugin.integration.update

import com.tracel.annotations.Observes
import com.tracel.plugin.command.permission.Permission
import com.tracel.plugin.command.permission.has
import com.tracel.plugin.i18n.say
import com.tracel.plugin.i18n.tr
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.JoinConfiguration
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.plugin.Plugin

private const val SHOW_AFTER_TICKS = 40L

/** Tells operators who join that this `Tracel` is a development build, and what that means. */
internal class DevBuildListener(private val version: String, private val plugin: Plugin) : Listener {
    @Observes
    fun onJoin(event: PlayerJoinEvent) {
        val player = event.player
        if (!player.has(Permission.STATUS)) return
        player.scheduler.runDelayed(plugin, { _ ->
            player.say(
                Component.join(
                    JoinConfiguration.newlines(),
                    tr("update.dev_title", "version" to version),
                    Component.empty(),
                    tr("update.ran", "version" to version),
                    tr("update.dev_advice"),
                )
            )
        }, null, SHOW_AFTER_TICKS)
    }
}
