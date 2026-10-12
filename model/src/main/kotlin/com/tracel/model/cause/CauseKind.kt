package com.tracel.model.cause

/** Why a transaction happened. Recorded on every transaction. */
public enum class CauseKind {
    PLAYER_ACTION,
    EXPLOSION,
    MACHINE,
    CRAFT,
    BLOCK_BREAK,
    ROLLBACK,
    INVOLUTION,
    ENTITY_ACTION,
    NATURAL,
    PLUGIN,
    UNKNOWN,
    WEAR,
    PROJECTILE,
    LIGHTNING,
    FIRE,
    FLUID,
    GROWTH;

    /**
     * Rollback and undo bookkeeping. The job record is the reversible layer; lookup must not treat
     * these as ordinary history, or every undo / rollback round-trip would re-read itself.
     */
    public val isBookkeeping: Boolean get() = this == ROLLBACK || this == INVOLUTION

    /** Nobody did it: the world on its own, [NATURAL] when nothing more specific is known. */
    public val isNatural: Boolean get() = this in NATURAL_CAUSES

    /** Every cause that is the world's doing; hidden from lookups unless asked for. */
    public companion object {
        public val NATURAL_CAUSES: Set<CauseKind> = setOf(
            NATURAL,
            LIGHTNING,
            FIRE,
            FLUID,
            GROWTH,
        )
    }
}
