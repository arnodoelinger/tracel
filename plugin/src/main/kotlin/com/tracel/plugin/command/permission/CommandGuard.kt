package com.tracel.plugin.command.permission

import com.tracel.annotations.Observes
import com.tracel.annotations.Priority
import com.tracel.plugin.command.tree.HelpTopic
import com.tracel.plugin.i18n.send
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import java.util.Locale

/** Aliases the root command answers to, besides its namespaced form. */
private val ROOTS = setOf("tracel", "tr")

/** The `Bukkit` namespace a player can put in front of a command. */
private const val NAMESPACE = "tracel:"

/** What the one command with no help line asks for. */
private const val TELEPORT = "tp"

/**
 * Tells a player why a `/tracel` command is not there.
 *
 * A command the sender may not use is hidden from the tree, so `Brigadier` would call it unknown. This turns
 * that into an honest "no permission", while tab completion keeps showing only what they can run.
 */
internal class CommandGuard : Listener {
    private val needs: Map<String, Permission> =
        HelpTopic.entries.associate { it.key to it.permission } + (TELEPORT to Permission.LOOKUP)

    /** Refuses a known subcommand the player lacks the permission for. */
    @Observes(priority = Priority.LOWEST, ignoreCancelled = false)
    fun onCommand(event: PlayerCommandPreprocessEvent) {
        val words = event.message.removePrefix("/").trim().split(' ').filter { it.isNotEmpty() }
        val root = words.firstOrNull()?.lowercase(Locale.ROOT)?.removePrefix(NAMESPACE) ?: return
        if (root !in ROOTS) return
        val permission = needs[words.getOrNull(1)?.lowercase(Locale.ROOT)] ?: return
        if (event.player.has(permission)) return
        event.isCancelled = true
        event.player.send("common.no_permission")
    }
}
