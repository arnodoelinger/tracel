package com.tracel.plugin.command.presenter

import com.tracel.model.transaction.Transaction
import com.tracel.model.world.WorldChange
import com.tracel.plugin.dialog.renderLookupResult
import com.tracel.plugin.dialog.renderWorldChange
import com.tracel.plugin.command.args.ParsedLookupArgs
import com.tracel.plugin.command.args.ScopeArgument
import org.bukkit.command.CommandSender

// TODO: rewrite
object LookupPresenter {
    const val LOOKUP_PAGE: Int = 100

    const val USAGE: String = "Usage: /tracel lookup u:<name> t:<2h|today|yesterday> scope:<Nb|Nc> " +
            "w:<world> i:<item> a:<action> -u:<name>"

    fun usage(sender: CommandSender) {
        sender.sendMessage(USAGE)
    }

    fun renderResults(
        sender: CommandSender,
        results: List<Transaction>,
        worldChanges: List<WorldChange>,
        parsed: ParsedLookupArgs,
        limit: Int = LOOKUP_PAGE,
        flushed: Boolean = true,
    ) {
        if (results.isEmpty() && worldChanges.isEmpty()) {
            sender.sendMessage("No matches.")
            return
        }

        if (!flushed) {
            sender.sendMessage("Some captures had not finished being written — this page may be missing the last moment.")
        }
        sender.sendMessage(lookupHeadline(parsed))
        val lines = results.map { it.seq.raw to renderLookupResult(it, parsed.item) } +
                worldChanges.map { it.seq.raw to listOf(renderWorldChange(it)) }
        lines.sortedByDescending { it.first }
            .take(limit)
            .flatMap { it.second }
            .forEach(sender::sendMessage)

        if (results.size + worldChanges.size >= limit) {
            sender.sendMessage("Showing the newest $limit — narrow it with t:, u:, i: or scope: to see the rest.")
        }
    }

    fun lookupHeadline(parsed: ParsedLookupArgs): String = buildString {
        append("Search results")
        val filters = buildList {
            if (parsed.users.isNotEmpty()) add("player ${parsed.users.joinToString(", ")}")
            parsed.item?.let { add("item $it") }
            if (parsed.actions.isNotEmpty()) add("action ${parsed.actions.joinToString(", ")}")
            if (parsed.since != null || parsed.until != null) add("time filter active")
            parsed.scope?.let { add("scope ${ScopeArgument.describe(it)}") }
            parsed.world?.let { add("world $it") }
        }
        if (filters.isNotEmpty()) {
            append(" (")
            append(filters.joinToString(" · "))
            append(')')
        }
    }
}
