package com.tracel.plugin.command.args.rollback

import com.tracel.api.history.HistoryQuery
import com.tracel.plugin.command.args.action.ActionFilter
import net.kyori.adventure.text.Component
import org.bukkit.Location

sealed interface FilterResult {
    data class Ok(
        val query: HistoryQuery,
        val center: Location?,
        val actions: ActionFilter,
    ) : FilterResult

    data class Rejected(val reasons: List<Component>) : FilterResult
}
