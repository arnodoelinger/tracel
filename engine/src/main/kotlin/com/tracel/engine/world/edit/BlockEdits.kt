package com.tracel.engine.world.edit

import com.tracel.model.cause.CauseKind
import com.tracel.model.holder.HolderId
import com.tracel.model.world.ActionKind

/**
 * Block edits that happened together: one [action], for one [cause], by one [causedBy] (nobody, if the world did it),
 * all at [epochMillis]. A blast or a push is one of these however many cells it touched.
 */
public data class BlockEdits(
    public val action: ActionKind,
    public val cause: CauseKind,
    public val causedBy: HolderId?,
    public val epochMillis: Long,
    public val edits: List<BlockEdit>,
)
