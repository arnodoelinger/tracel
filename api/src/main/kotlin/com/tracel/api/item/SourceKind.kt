package com.tracel.api.item

import com.tracel.api.Unstable

/**
 * Why items came out of nowhere.
 *
 * @since 1.0.x
 */
@Unstable
public enum class SourceKind {
    MOB_DROP,
    CRAFT,
    CREATIVE,
    COMMAND,
    WORLDGEN,
    ROLLBACK_MINT,
    UNTRACKED_GAP,
    UNATTRIBUTED,
    PLUGIN,
}
