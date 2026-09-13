package com.tracel.engine.world

import com.tracel.annotations.CauseKind
import com.tracel.model.holder.HolderId
import com.tracel.model.world.ActionKind
import com.tracel.model.world.BlockPos
import com.tracel.model.world.block.BlockShape

/** One coordinate going from one shape to another. */
public data class BlockEdit(
    public val at: BlockPos,
    public val before: BlockShape,
    public val after: BlockShape,
)

/** Block edits that happened together, for one reason. */
public data class BlockEdits(
    public val action: ActionKind,
    public val cause: CauseKind,
    public val causedBy: HolderId?,
    public val epochMillis: Long,
    public val edits: List<BlockEdit>,
)
