package com.tracel.api.history

import com.tracel.api.Unstable

/** Why something happened. Every record has one. */
@Unstable
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
    GROWTH,
    HOPPER,
    DISPENSER,
    DROPPER;

    /** A rollback's or an undo's own bookkeeping: history does not show it as what happened. */
    public val isBookkeeping: Boolean get() = this == ROLLBACK || this == INVOLUTION

    /** The world on its own. Hidden from history unless the query asks for it. */
    public val isNatural: Boolean get() = this in NATURAL_CAUSES

    public companion object {
        @JvmField
        public val NATURAL_CAUSES: Set<CauseKind> = setOf(CauseKind.NATURAL, LIGHTNING, FIRE, FLUID, GROWTH)
    }
}
