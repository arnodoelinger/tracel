package com.tracel.model.holder

/** Why a unit was minted — i.e. why it entered the ledger with no prior lot. */
public enum class SourceKind {
    MOB_DROP,
    CRAFT,
    CREATIVE,
    COMMAND,
    WORLDGEN,
    ROLLBACK_MINT,
    UNTRACKED_GAP,
    UNATTRIBUTED,
}
