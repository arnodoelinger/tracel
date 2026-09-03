package com.tracel.engine.rollback.job

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
public interface RollbackJobRepository {
    public companion object {
        public const val UNDO_DEPTH: Int = 20
    }

    public suspend fun save(record: RollbackJobRecord)

    public suspend fun begin(record: RollbackJobRecord): SaveHandle = SaveHandle(record, 0)

    public suspend fun finish(handle: SaveHandle, destroy: List<StructureStep>) {
        save(handle.record.copy(destroy = destroy))
    }

    public suspend fun find(id: RollbackJobId): RollbackJobRecord?

    public suspend fun undoable(limit: Int = 1): List<RollbackJobId>

    public suspend fun isUndoable(id: RollbackJobId): Boolean

    public suspend fun markUndone(id: RollbackJobId)
}

/** What [RollbackJobRepository.begin] wrote, and where [RollbackJobRepository.finish] carries on. */
public class SaveHandle(public val record: RollbackJobRecord, public val fromRun: Int)
