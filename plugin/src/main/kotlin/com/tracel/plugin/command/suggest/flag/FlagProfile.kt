package com.tracel.plugin.command.suggest.flag

internal enum class FlagProfile {
    LOOKUP,
    ROLLBACK,
    PRESET,
}

internal fun FlagProfile.accepts(flag: FlagToken) =
    if (this == FlagProfile.PRESET) flag.profiles.isNotEmpty() else this in flag.profiles
