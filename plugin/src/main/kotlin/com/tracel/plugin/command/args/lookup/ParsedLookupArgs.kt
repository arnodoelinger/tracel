package com.tracel.plugin.command.args.lookup

import com.tracel.api.addon.QueryPart
import com.tracel.api.history.HistoryQuery
import com.tracel.plugin.command.args.scope.LookupScope
import net.kyori.adventure.text.Component
import org.bukkit.Location

/** Parsed lookup arguments. */
data class ParsedLookupArgs(
    val users: Set<String> = emptySet(),
    val item: String? = null,
    val actions: Set<String> = emptySet(),
    val since: Long? = null,
    val until: Long? = null,
    val scope: LookupScope? = null,
    val world: String? = null,
    val horizontalOnly: Boolean = false,
    val preview: Boolean = false,
    val structureOnly: Boolean = false,
    val materialOnly: Boolean = false,
    val strict: Boolean = false,
    val confirmed: Boolean = false,
    val natural: Boolean = false,
    val turnings: Boolean = false,
    val all: Boolean = false,
    val each: Boolean = false,
    val page: Int = 1,
    val anchor: Location? = null,
    val command: String = "",
    val errors: List<Component> = emptyList(),
    val parts: List<QueryPart> = emptyList(),
)

/** [query], narrowed by every flag an addon added to the command line, in the order they were typed. */
internal fun ParsedLookupArgs.withParts(query: HistoryQuery): HistoryQuery =
    parts.fold(query) { narrowed, part -> narrowed.with(part::narrow) }

/** Whether an addon's flag names an area, which stands in for `scope:` where one is needed. */
internal val ParsedLookupArgs.partsLimitArea: Boolean get() = withParts(HistoryQuery()).area != null
