package com.tracel.api.addon

import com.tracel.api.Unstable
import com.tracel.api.actor.Actor
import com.tracel.api.rollback.RollbackJobId
import net.kyori.adventure.text.Component

/** A rollback as `@OnRollbackStarted` and `@OnRollbackFinished` see it: the same value in both. */
@Unstable
public data class RollbackJob(
    /** Counts rollbacks since `Tracel` started. Tells two apart, nothing more; not the job number. */
    public val run: Long,

    /** Who asked for it: a player, a plugin. `null` for the console. */
    public val by: Actor?,

    /** How many records it takes back. */
    public val records: Int,
)

/** How a rollback ended. */
@Unstable
public sealed interface RollbackOutcome {
    /** It ran as rollback [job], for `Rollbacks.undo`. */
    public data class Done(
        public val job: RollbackJobId,
        public val changed: Int,
        public val skipped: Int,
        public val overwritten: Int,
        public val itemsRestored: Boolean,
    ) : RollbackOutcome

    /** It changed nothing, and [reason] says why. */
    public data class Refused(public val reason: String) : RollbackOutcome

    /** It broke partway. What was done is in the rollback journal and finishes on its own. */
    public data class Failed(public val message: String) : RollbackOutcome
}

/** Lines an `@OnInspect` handler adds under a block's history. */
@Unstable
public interface InspectLines {
    /** Adds [line] at the end. */
    public fun add(line: Component)

    /** Adds [text] at the end, plain. */
    public fun add(text: String): Unit = add(Component.text(text))
}
