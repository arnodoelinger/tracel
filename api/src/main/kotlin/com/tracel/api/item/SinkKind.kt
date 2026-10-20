package com.tracel.api.item

import com.tracel.api.Unstable

/** Why items left for good. */
@Unstable
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
