package com.tracel.api.rollback

import com.tracel.api.Unstable
import com.tracel.api.actor.Actor
import com.tracel.api.item.ItemHolder
import com.tracel.api.world.NamespacedName

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

    /** Some items it needs are held by other rollbacks, and [holds] says whose. */
    public data class Blocked(public val holds: List<Hold>) : RollbackError {
        /** [lots] lots of items held for [job], which [by] ran; `null` for the console. */
        public data class Hold(public val job: RollbackJobId, public val by: Actor?, public val lots: Int)
    }

    /** Somewhere it must write is out of reach: [holder] is the one it could not reach, and [reason] says why. */
    public data class Unreachable(public val holder: ItemHolder, public val reason: String) : RollbackError

    /**
     * It reaches into [holder], a container of an addon that is not installed, and [reason] says what went wrong:
     * nobody can say how to put items back there, so nothing is put back anywhere.
     */
    public data class AddonMissing(public val holder: ItemHolder.Custom, public val reason: String) : RollbackError {
        /** The type of container the missing addon owns. */
        public val kind: NamespacedName get() = holder.kind
    }

    /** There is nothing to roll back. */
    public data object NothingToDo : RollbackError

    /**
     * The job is not one this plugin can undo: another's, unknown, or undone already.
     *
     * An undone job is forgotten, so these cannot be told apart.
     */
    public data object NotUndoable : RollbackError

    /** Rollbacks [newer] touched the same things later; undo those first. */
    public data class OutOfOrder(public val newer: List<RollbackJobId>) : RollbackError

    /** The query asks something a rollback cannot do, and [reason] says what. */
    public data class Unsupported(public val reason: String) : RollbackError

    /**
     * It broke, and [message] says what's going on.
     *
     * What was done is in the rollback journal and finishes on its own.
     */
    public data class Failed(public val message: String) : RollbackError
}
