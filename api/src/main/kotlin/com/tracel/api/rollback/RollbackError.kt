package com.tracel.api.rollback

import com.tracel.api.Unstable

/**
 * Why a rollback step did not happen.
 *
 * Nothing was changed, unless [Failed] says something else.
 */
@Unstable
public sealed interface RollbackError {
    /** This handle was already used. */
    public data object Moved : RollbackError

    /** It came from a `borrow` block that has ended. */
    public data object Expired : RollbackError

    /** The connection was closed or the plugin disabled. */
    public data object Revoked : RollbackError

    /** Another rollback or undo is running; only one runs at a time. */
    public data object Busy : RollbackError

    /** [by] holds part of the area. */
    public data class Borrowed(public val by: String) : RollbackError

    /** A draft made inside a `borrow` reaches outside the borrowed area. */
    public data object OutsideBorrow : RollbackError

    /** The query has no time or no area, and [missing] says which. */
    public data class Unbounded(public val missing: String) : RollbackError

    /** History moved on since the plan was made; [replan] is the plan that holds now. */
    public data class Stale(public val replan: RollbackReady) : RollbackError

    /** It would make [lots] lots of items out of nothing, and the draft did not `allowMint()`. */
    public data class WouldMint(public val lots: Int) : RollbackError

    /** It asks for [asked] of [what], past the [limit] the admin set. */
    public data class OverBudget(
        public val what: String,
        public val limit: Long,
        public val asked: Long,
    ) : RollbackError

    /** Some items it needs are held by another rollback. */
    public data object Blocked : RollbackError

    /** Somewhere it must write is out of reach, and [reason] says why. */
    public data class Unreachable(public val reason: String) : RollbackError

    /** There is nothing to roll back. */
    public data object NothingToDo : RollbackError

    /**
     * The job is not one this plugin can undo: another's, unknown, or undone already.
     *
     * An undone job is forgotten, so these cannot be told apart.
     */
    public data object NotUndoable : RollbackError

    /** Rollbacks [newer] touched the same things later; undo those first. */
    public data class OutOfOrder(public val newer: List<Long>) : RollbackError

    /** The query asks something a rollback cannot do, and [reason] says what. */
    public data class Unsupported(public val reason: String) : RollbackError

    /**
     * It broke, and [message] says what's going on.
     *
     * What was done is in the rollback journal and finishes on its own.
     */
    public data class Failed(public val message: String) : RollbackError
}
