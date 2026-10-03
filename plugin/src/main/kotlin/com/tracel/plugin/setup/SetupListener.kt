package com.tracel.plugin.setup

import com.tracel.plugin.TracelServices
import com.tracel.annotations.Observes
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent

/** How long to wait before showing the setup to a joining operator. */
private const val SHOW_AFTER_TICKS = 20L

/** Shows the welcome setup to an operator who joins while it is still waiting. */
internal class SetupListener(private val services: TracelServices, private val state: SetupState) : Listener {
    private val wizard = SetupWizard(services, state)

    companion object {
        const val PERMISSION = "tracel.setup"
    }

    /** Shows the welcome setup to an operator who joins while it is still waiting. */
    @Observes
    fun onJoin(event: PlayerJoinEvent) {
        val player = event.player
        if (!state.pending || !player.hasPermission(PERMISSION)) return
        player.scheduler.runDelayed(services.plugin, { if (state.pending) wizard.open(player) }, null, SHOW_AFTER_TICKS)
    }
}
