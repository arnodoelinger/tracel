package com.tracel.model.holder

/** Why a unit left the ledger for good — i.e. where it was burned. */
public enum class SinkKind {
    LAVA,
    DESPAWN,
    BURN_FUEL,
    CRAFT_CONSUME,
    COMMAND,
    ROLLBACK_BURN,
    UNTRACKED_GAP,
    UNATTRIBUTED,
}
