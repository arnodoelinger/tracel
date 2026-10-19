package com.tracel.api.rollback

import com.tracel.api.Unstable
import com.tracel.api.result.Res

/** A rollback that ran. Its [undo] is its exact inverse, and can be called once. */
@Unstable
public interface RollbackApplied {
    /** The job, for `Rollbacks.undo` after a restart. */
    public val job: Long

    /** Blocks and entities it changed. */
    public val changed: Int

    /** Blocks and entities it left alone: changed since, out of reach. */
    public val skipped: Int

    /** Blocks it put back over somebody's later change. */
    public val overwritten: Int

    /** Whether every item went where it had to. */
    public val itemsRestored: Boolean

    /** Takes it back. A second call answers [RollbackError.Moved]. */
    public suspend fun undo(): Res<RollbackUndone, RollbackError>
}

/** A rollback that was taken back. */
@Unstable
public data class RollbackUndone(
    public val job: Long,
    public val changed: Int,
    public val skipped: Int,
    public val itemsRestored: Boolean,
)
