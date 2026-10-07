package com.tracel.plugin.command.suggest.flag

private val BOTH = setOf(FlagProfile.LOOKUP, FlagProfile.ROLLBACK)

private val ROLLBACK_ONLY = setOf(FlagProfile.ROLLBACK)

internal val FLAGS: List<FlagToken> = listOf(
    FlagToken(
        aliases = listOf("#preview"),
        tooltip = "preview",
        kind = FlagKind.SWITCH,
        group = FlagGroup.PREVIEW,
        profiles = ROLLBACK_ONLY
    ),
    FlagToken(
        aliases = listOf("#blocks"),
        tooltip = "blocks",
        kind = FlagKind.SWITCH,
        group = FlagGroup.MODE_BLOCKS,
        profiles = BOTH,
        exclusiveWith = setOf(FlagGroup.MODE_ITEMS)
    ),
    FlagToken(
        aliases = listOf("#items"),
        tooltip = "items",
        kind = FlagKind.SWITCH,
        group = FlagGroup.MODE_ITEMS,
        profiles = BOTH,
        exclusiveWith = setOf(FlagGroup.MODE_BLOCKS)
    ),
    FlagToken(
        aliases = listOf("#explosion"),
        tooltip = "explosion",
        kind = FlagKind.SWITCH,
        group = FlagGroup.EXPLOSION,
        profiles = BOTH
    ),
    FlagToken(
        aliases = listOf("#strict"),
        tooltip = "strict",
        kind = FlagKind.SWITCH,
        group = FlagGroup.STRICT,
        profiles = ROLLBACK_ONLY
    ),
    FlagToken(
        aliases = listOf("#confirm"),
        tooltip = "confirm",
        kind = FlagKind.SWITCH,
        group = FlagGroup.CONFIRM,
        profiles = ROLLBACK_ONLY
    ),
    FlagToken(
        aliases = listOf("#each"),
        tooltip = "each",
        kind = FlagKind.SWITCH,
        group = FlagGroup.EACH,
        profiles = setOf(FlagProfile.LOOKUP)
    ),
    FlagToken(
        aliases = listOf("#world"),
        tooltip = "natural",
        kind = FlagKind.SWITCH,
        group = FlagGroup.NATURAL,
        profiles = setOf(FlagProfile.LOOKUP)
    ),
    FlagToken(
        aliases = listOf("#all"),
        tooltip = "all",
        kind = FlagKind.SWITCH,
        group = FlagGroup.NATURAL,
        profiles = setOf(FlagProfile.LOOKUP)
    ),
    FlagToken(
        aliases = listOf("#wide"),
        tooltip = "wide",
        kind = FlagKind.SWITCH,
        group = FlagGroup.WIDE,
        profiles = BOTH
    ),
    FlagToken(
        aliases = listOf("u:", "user:"),
        tooltip = "user",
        kind = FlagKind.SET,
        group = FlagGroup.USERS,
        profiles = BOTH
    ),
    FlagToken(
        aliases = listOf("t:", "time:", "after:", "before:"),
        tooltip = "time",
        kind = FlagKind.VALUE,
        group = FlagGroup.TIME,
        profiles = BOTH
    ),
    FlagToken(
        aliases = listOf("s:", "scope:"),
        tooltip = "scope",
        kind = FlagKind.VALUE,
        group = FlagGroup.SCOPE,
        profiles = BOTH
    ),
    FlagToken(
        aliases = listOf("w:", "world:"),
        tooltip = "world",
        kind = FlagKind.VALUE,
        group = FlagGroup.WORLD,
        profiles = BOTH
    ),
    FlagToken(
        aliases = listOf("i:", "item:"),
        tooltip = "item",
        kind = FlagKind.VALUE,
        group = FlagGroup.MATERIAL,
        profiles = BOTH
    ),
    FlagToken(
        aliases = listOf("b:", "block:"),
        tooltip = "block",
        kind = FlagKind.VALUE,
        group = FlagGroup.MATERIAL,
        profiles = BOTH
    ),
    FlagToken(
        aliases = listOf("a:", "action:"),
        tooltip = "action",
        kind = FlagKind.SET,
        group = FlagGroup.ACTION,
        profiles = BOTH
    )
)
