package com.tracel.engine.rollback.job

import com.tracel.annotations.Unstable
import com.tracel.engine.rollback.involution.InvolutionPlanner
import com.tracel.engine.rollback.structure.StructureStep
import com.tracel.model.id.RollbackJobId

/**
 * Storage port for [RollbackJobRecord]s — what [InvolutionPlanner]
 * needs to reverse a job.
 *
 * It also keeps the undo stack, which is the only reason anyone ever has to know a job number.
 * Asking an admin to read one off a chat line and type it back is not a user interface; asking
 * them to say "undo that" is! [undoable] is what makes the second one possible.
 */
@Unstable
public interface RollbackJobRepository {
    public companion object {
        /** How many jobs [undoable] keeps for "undo that". */
        public const val UNDO_DEPTH: Int = 20
    }

    /** Persist a finished job and push it on the undo stack if it is not already there. */
    public suspend fun save(record: RollbackJobRecord)

    /** Start a persist; [finish] writes destroy steps once structure apply has them. */
    public suspend fun begin(record: RollbackJobRecord): SaveHandle = SaveHandle(record, 0)

    /** Complete [begin]: attach [destroy] and [save]. */
    public suspend fun finish(handle: SaveHandle, destroy: List<StructureStep>) {
        save(handle.record.copy(destroy = destroy))
    }

    /** Job by [id], including ones already [markUndone]. */
    public suspend fun find(id: RollbackJobId): RollbackJobRecord?

    /** Newest-first IDs still on the undo stack, at most [limit] (capped by [UNDO_DEPTH] in storage). */
    public suspend fun undoable(limit: Int = 1): List<RollbackJobId>

    /** Whether [id] is still on the undo stack. */
    public suspend fun isUndoable(id: RollbackJobId): Boolean

    /** Drop [id] from the undo stack. The record stays for [find] / involution. */
    public suspend fun markUndone(id: RollbackJobId)
}
