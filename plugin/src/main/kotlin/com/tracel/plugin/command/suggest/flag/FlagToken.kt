package com.tracel.plugin.command.suggest.flag

internal data class FlagToken(
    val aliases: List<String>,
    val tooltip: String,
    val kind: FlagKind,
    val group: FlagGroup,
    val profiles: Set<FlagProfile>,
    val exclusiveWith: Set<FlagGroup> = emptySet(),
    val quiet: Boolean = false, // Hidden until the typed token actually reaches for it
)

internal data class MatchedFlag(val flag: FlagToken, val alias: String)
