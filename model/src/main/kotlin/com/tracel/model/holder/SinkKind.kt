package com.tracel.model.holder

/** Why a unit left the ledger for good — i.e. where it was destroyed. */
public enum class SinkKind {
    HAZARD,
    DESPAWN,
    BURN_FUEL,
    CRAFT_CONSUME,
    COMMAND,
    ROLLBACK_BURN,
    UNTRACKED_GAP,
    UNATTRIBUTED,
    CREATIVE,
}
