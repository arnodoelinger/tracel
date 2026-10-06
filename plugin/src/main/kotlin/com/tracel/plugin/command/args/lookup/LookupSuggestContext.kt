package com.tracel.plugin.command.args.lookup

/** Suggest context for lookup command. */
internal data class LookupSuggestContext(
    val onlinePlayerNames: List<String>,
    val worldNames: List<String>,
    val causeNames: List<String>,
    val itemNames: List<String> = emptyList(),
    val blockNames: List<String> = emptyList(),
)
