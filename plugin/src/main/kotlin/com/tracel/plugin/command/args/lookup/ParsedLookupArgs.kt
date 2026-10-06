package com.tracel.plugin.command.args.lookup

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
    val all: Boolean = false,
    val each: Boolean = false,
    val page: Int = 1,
    val anchor: Location? = null,
    val command: String = "",
    val errors: List<Component> = emptyList(),
)
