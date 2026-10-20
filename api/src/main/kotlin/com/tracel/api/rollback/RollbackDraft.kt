package com.tracel.api.rollback

import com.tracel.api.Unstable
import com.tracel.api.history.HistoryQuery
import com.tracel.api.result.Res

/** A rollback not yet planned. Immutable: each option gives a new draft. */
@Unstable
public interface RollbackDraft {
    /** What it rolls back. */
    public val query: HistoryQuery

    /** What happens to blocks somebody changed again after the history being rolled back. */
    public val conflicts: ConflictPolicy

    /** Whether it may make items out of nothing to stand in for ones that are gone. */
    public val mintAllowed: Boolean

    /** The same draft, with [policy] for blocks changed since. */
    public fun onConflict(policy: ConflictPolicy): RollbackDraft

    /** The same draft, allowed to mint. Every minted item is written down as a rollback mint. */
    public fun allowMint(): RollbackDraft

    /** Plans it, changing nothing. */
    public suspend fun preview(): Res<RollbackReady, RollbackError>
}

/** What a rollback does to a block somebody changed again after the history it rolls back. */
@Unstable
public enum class ConflictPolicy {
    /** Leave it as it is now. */
    SKIP,

    /** Put it back anyway, over the later change. */
    OVERWRITE,
}
