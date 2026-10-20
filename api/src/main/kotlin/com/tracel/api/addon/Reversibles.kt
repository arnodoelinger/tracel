package com.tracel.api.addon

import com.tracel.api.Unstable

/**
 * How to take back a custom event that changed something outside the world.
 *
 * A rollback that reaches such a record calls [undo]; undoing that rollback calls [redo].
 *
 * Example:
 * ```
 * object Refunds : Reversible<Payment> {
 *     override fun undo(step: RollbackStep, value: Payment) = bank.once(step.id) { bank.move(value.to, value.from, value.amount) }
 *     override fun redo(step: RollbackStep, value: Payment) = bank.once(step.id) { bank.move(value.from, value.to, value.amount) }
 * }
 * ```
 *
 * Called off the main thread, one call at a time per addon. In order; hop to the server thread yourself when what you
 * touch needs it. A call is made at least once, for example, after a crash, the one that may not have finished is made
 * again with the same [RollbackStep.id], so keep the IDs you have done and skip a repeat.
 *
 * Throwing leaves the step for later.
 */
@Unstable
public interface Reversible<T : Any> {
    /** Takes [value] back: a rollback reached it. */
    public fun undo(step: RollbackStep, value: T)

    /** Does [value] again: the rollback that took it back was undone. */
    public fun redo(step: RollbackStep, value: T)
}

/** One call of [Reversible.undo] or [Reversible.redo]. */
@Unstable
public data class RollbackStep(
    /** The same for a repeat of this call after a crash, but different for every other call. */
    public val id: String,

    /** The rollback that took the record back, when known; for an undo, the one being undone. */
    public val job: Long?,
)

/**
 * Where reversible event types are registered.
 *
 * Needs the `rollback` grant.
 */
@Unstable
public interface Reversibles {
    /**
     * Registers [type] as an event type, as [EventTypes.register] does, whose records
     * a rollback takes back through [how].
     *
     * Steps left over from before a restart are finished right after this.
     *
     * Its records stay after the addon is gone; a rollback that reaches one then refuses, it does not half-apply.
     */
    public fun <T : Any> register(type: CustomEventType<T>, how: Reversible<T>): RegisteredEvent<T>
}
