package com.tracel.plugin.command.tree

import com.tracel.plugin.command.action.LookupAction
import com.tracel.plugin.command.args.lookup.ParsedLookupArgs
import com.tracel.plugin.command.args.scope.LookupScope
import com.tracel.plugin.command.preset.PresetStore
import com.tracel.plugin.command.preset.parseWithPresets
import com.tracel.plugin.i18n.send
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

private const val NEAR_WINDOW = 15 * 60_000L
private const val NEAR_RADIUS = 16

/** Runs the sender's last search again, on the page [flags] asks for. */
internal fun refresh(sender: CommandSender, flags: List<String>, store: PresetStore, lookup: LookupAction) {
    val (command, anchor) = lookup.last(sender) ?: return sender.send("lookup.no_search")
    val page =
        flags.firstNotNullOfOrNull { it.removePrefix("p:").toIntOrNull()?.takeIf { _ -> it.startsWith("p:") } } ?: 1
    val words = tokens(command)
    val tokens = words.drop(1)
    val parsed =
        if (words.first() == "near") nearby(sender, tokens, store) else parsePresetted(sender, tokens, store)
    lookup.execute(sender, parsed.rerunnable(words.first(), tokens).copy(page = page, anchor = anchor))
}

/** `/tracel near`: a lookup around the sender, over the last minutes unless [tokens] say otherwise. */
internal fun runNear(sender: CommandSender, tokens: List<String>, store: PresetStore, lookup: LookupAction) {
    lookup.execute(sender, nearby(sender, tokens, store).rerunnable("near", tokens))
}

private fun nearby(sender: CommandSender, tokens: List<String>, store: PresetStore): ParsedLookupArgs {
    val parsed = parsePresetted(sender, tokens, store)
    val now = System.currentTimeMillis()
    return parsed.copy(
        since = parsed.since ?: if (parsed.until == null) now - NEAR_WINDOW else null,
        scope = parsed.scope ?: if (parsed.world == null) LookupScope.Blocks(NEAR_RADIUS) else null,
    )
}

/** Parses [tokens] as lookup flags, with the sender's presets and the server's filled in. */
internal fun parsePresetted(sender: CommandSender, tokens: List<String>, store: PresetStore) = parseWithPresets(
    tokens,
    (sender as? Player)?.uniqueId,
    store,
    System.currentTimeMillis(),
)

/** These arguments with the command line that produced them, minus the page, so it can be run again. */
internal fun ParsedLookupArgs.rerunnable(name: String, tokens: List<String>) =
    copy(
        command = (listOf(name) + tokens.filterNot { it.startsWith("page:") || it.startsWith("p:") }).joinToString(
            " "
        )
    )

/** A command line cut into its words. */
internal fun tokens(line: String): List<String> =
    line.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
