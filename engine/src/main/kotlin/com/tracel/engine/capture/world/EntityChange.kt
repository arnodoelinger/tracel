package com.tracel.engine.capture.world

import com.tracel.model.cause.CauseKind
import com.tracel.model.holder.HolderId
import com.tracel.model.world.ActionKind
import com.tracel.model.world.BlockPos
import com.tracel.model.world.entity.EntityShape
import java.util.*

/** One entity appearing, changing or going away, as the region thread saw it. */
public data class EntityChange(
    public val action: ActionKind,
    public val cause: CauseKind,
    public val causedBy: HolderId?,
    public val epochMillis: Long,
    public val at: BlockPos,
    public val entity: UUID,
    public val before: EntityShape?,
    public val after: EntityShape?,
)
