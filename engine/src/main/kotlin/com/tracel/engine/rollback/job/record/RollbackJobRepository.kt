package com.tracel.engine.rollback.job.record

import com.tracel.engine.rollback.involution.plan.InvolutionPlanner
import com.tracel.engine.rollback.structure.StructureStep
import com.tracel.model.holder.HolderId
import com.tracel.model.rollback.RollbackJobId

/**
 * Storage port for [RollbackJobRecord]s — what [InvolutionPlanner]
 * needs to reverse a job.
 *
 * It also keeps the undo stack, which is the only reason anyone ever has to know a job number.
 * Asking an admin to read one off a chat line and type it back is not a user interface; asking
 * them to say "undo that" is! [undoable] is what makes the second one possible.
 */
public interface RollbackJobRepository {
    public companion object {
        public const val UNDO_DEPTH: Int = 20
    }

    /** Persists a finished job and pushes it on the undo stack, unless it is there already. */
    public suspend fun save(record: RollbackJobRecord)

    /**
     * Starts a persist, for a job whose destroy steps are not known yet. [finish] writes them once the structure
     * apply has them. The default does nothing until then.
     */
    public suspend fun begin(record: RollbackJobRecord): SaveHandle = SaveHandle(record, 0)

    /** Completes what [begin] started: attaches [destroy] to its record and saves it. */
    public suspend fun finish(handle: SaveHandle, destroy: List<StructureStep>) {
        save(handle.record.copy(destroy = destroy))
    }

    /** Job by [id], while its record is kept: [markUndone] and eviction past [UNDO_DEPTH] forget it. */
    public suspend fun find(id: RollbackJobId): RollbackJobRecord?

    /** Ids on anyone's undo stack, newest first, at most [limit]. */
    public suspend fun undoable(limit: Int = 1): List<RollbackJobId>

    /** Ids on [by]'s own undo stack, newest first, at most [limit]. */
    public suspend fun undoableBy(by: HolderId?, limit: Int = 1): List<RollbackJobId>

    /** Whether [id] is still on an undo stack, and so can be undone. */
    public suspend fun isUndoable(id: RollbackJobId): Boolean

    /** Takes [id] off the undo stack and forgets its record. An undone job has nothing left to undo. */
    public suspend fun markUndone(id: RollbackJobId)
}
