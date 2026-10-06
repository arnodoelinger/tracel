package com.tracel.plugin.importer.coreprotect.translate

import com.tracel.engine.world.edit.BlockEdit
import com.tracel.engine.world.edit.BlockEdits
import com.tracel.model.cause.CauseKind
import com.tracel.model.holder.HolderId
import com.tracel.model.world.ActionKind
import com.tracel.model.world.BlockPos
import com.tracel.model.world.WorldId

private const val MAX_GROUP = 4_096

/** Edits that happened together — same action, actor and moment — up to [MAX_GROUP] of them and one per cell. */
internal class EditGroup(
    val action: ActionKind,
    val cause: CauseKind,
    val by: HolderId?,
    val millis: Long,
    val world: WorldId,
) {
    private val edits = ArrayList<BlockEdit>()
    private val seen = HashSet<Long>()

    fun takes(
        action: ActionKind,
        cause: CauseKind,
        by: HolderId?,
        millis: Long,
        world: WorldId,
        at: BlockPos
    ): Boolean =
        edits.size < MAX_GROUP && action == this.action && cause == this.cause && by == this.by &&
                millis == this.millis && world == this.world && Standing.key(at.x, at.y, at.z) !in seen

    fun add(edit: BlockEdit) {
        edits += edit
        seen += Standing.key(edit.at.x, edit.at.y, edit.at.z)
    }

    fun edits() = BlockEdits(action, cause, by, millis, edits)
}
