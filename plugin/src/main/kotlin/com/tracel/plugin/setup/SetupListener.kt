package com.tracel.plugin.setup

import com.tracel.annotations.Observes
import com.tracel.plugin.command.permission.Permission
import com.tracel.plugin.command.permission.has
import com.tracel.plugin.services.TracelServices
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent

/** How long to wait before showing the setup to a joining operator. */
private const val SHOW_AFTER_TICKS = 20L

/** Shows the welcome setup to an operator who joins while it is still waiting. */
internal class SetupListener(private val services: TracelServices, private val state: SetupState) : Listener {
    private val wizard = SetupWizard(services, state)

    /** Shows the welcome setup to an operator who joins while it is still waiting. */
    @Observes
    fun onJoin(event: PlayerJoinEvent) {
        val player = event.player
        if (!state.pending || !player.has(Permission.SETUP)) return
        player.scheduler.runDelayed(services.plugin, { if (state.pending) wizard.open(player) }, null, SHOW_AFTER_TICKS)
    }
}
