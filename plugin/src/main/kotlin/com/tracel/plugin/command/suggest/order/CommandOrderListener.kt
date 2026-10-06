package com.tracel.plugin.command.suggest.order

import com.destroystokyo.paper.event.brigadier.AsyncPlayerSendSuggestionsEvent
import com.tracel.annotations.Observes
import com.tracel.annotations.Priority
import org.bukkit.event.Listener

/** Puts the subcommand lists in [CommandOrder] just before they are sent. */
internal class CommandOrderListener : Listener {
    @Observes(priority = Priority.NORMAL, ignoreCancelled = false)
    fun onSuggestions(event: AsyncPlayerSendSuggestionsEvent) {
        event.suggestions = CommandOrder.order(event.buffer, event.suggestions)
    }
}
