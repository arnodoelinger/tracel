package com.tracel.model.transaction

/** Why a transaction happened. Recorded on every transaction. */
public enum class CauseKind {
    PLAYER_ACTION,
    EXPLOSION,
    HOPPER,
    CRAFT,
    BLOCK_BREAK,
    ROLLBACK,
    INVOLUTION,
    ENTITY_ACTION,
    WORLD,
    PLUGIN,
    UNKNOWN,
    WEAR,
    PROJECTILE;

    /**
     * Rollback and undo bookkeeping. The job record is the reversible layer; lookup must not treat
     * these as ordinary history, or every undo / rollback round-trip would re-read itself.
     */
    public val isBookkeeping: Boolean get() = this == ROLLBACK || this == INVOLUTION
}
